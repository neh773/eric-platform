import { describe, expect, it } from "vitest";
import {
  DEFAULT_JOURNAL_COLUMNS,
  journalCellText,
  journalLabel,
  journalQuery,
  parseJournalColumns,
  parseJournalPage,
  parsePriority,
  searchJournal,
  type JournalEventType,
  type JournalQueryOptions,
} from "./alarmJournalApi";

const allTypes: Record<JournalEventType, boolean> = { active: true, clear: true, ack: true, shelve: true, unshelve: true };
const NOW = Date.parse("2026-10-28T12:00:00Z");

function options(overrides: Partial<JournalQueryOptions> = {}): JournalQueryOptions {
  return {
    range: { mode: "realtime", minutes: 480, start: "", end: "" },
    sourceFilter: "",
    displayPathFilter: "",
    minPriority: "Diagnostic",
    maxPriority: "Critical",
    eventTypes: allTypes,
    offset: 0,
    limit: 100,
    newestFirst: true,
    ...overrides,
  };
}

function params(result: ReturnType<typeof journalQuery>): URLSearchParams {
  if (!result || !("path" in result)) throw new Error(`no path: ${JSON.stringify(result)}`);
  expect(result.path.startsWith("/api/v1/alarms/journal?")).toBe(true);
  return new URLSearchParams(result.path.split("?")[1]);
}

describe("journalQuery", () => {
  it("looks back the realtime window and leaves out filters that match everything", () => {
    const q = params(journalQuery(options(), NOW));
    expect(q.get("start")).toBe("2026-10-28T04:00:00.000Z");
    expect(q.has("end")).toBe(false);
    expect(q.get("eventType")).toBe("active,clear,ack,shelve,unshelve");
    expect(q.has("minPriority")).toBe(false);
    expect(q.has("maxPriority")).toBe(false);
    expect(q.has("offset")).toBe(false);
    expect(q.has("order")).toBe(false);
    expect(q.get("limit")).toBe("100");
  });

  it("passes source, display path, priority range, event types, paging and order", () => {
    const q = params(
      journalQuery(
        options({
          sourceFilter: " *:/tag:tank/* ",
          displayPathFilter: "Area1/*,Area2/*",
          minPriority: "Medium",
          maxPriority: "High",
          eventTypes: { ...allTypes, shelve: false, unshelve: false },
          offset: 200,
          limit: 5000,
          newestFirst: false,
        }),
        NOW,
      ),
    );
    expect(q.get("source")).toBe("*:/tag:tank/*");
    expect(q.get("displayPath")).toBe("Area1/*,Area2/*");
    expect(q.get("minPriority")).toBe("Medium");
    expect(q.get("maxPriority")).toBe("High");
    expect(q.get("eventType")).toBe("active,clear,ack");
    expect(q.get("offset")).toBe("200");
    expect(q.get("limit")).toBe("1000");
    expect(q.get("order")).toBe("asc");
  });

  it("swaps an inverted priority range", () => {
    const q = params(journalQuery(options({ minPriority: "Critical", maxPriority: "Low" }), NOW));
    expect(q.get("minPriority")).toBe("Low");
    expect(q.has("maxPriority")).toBe(false);
  });

  it("uses historical bounds as ISO instants and rejects bad or inverted dates", () => {
    const q = params(journalQuery(options({ range: { mode: "historical", minutes: 1, start: "2026-10-01T00:00:00Z", end: "2026-10-02T00:00:00Z" } }), NOW));
    expect(q.get("start")).toBe("2026-10-01T00:00:00.000Z");
    expect(q.get("end")).toBe("2026-10-02T00:00:00.000Z");
    const open = params(journalQuery(options({ range: { mode: "historical", minutes: 1, start: "", end: "" } }), NOW));
    expect(open.has("start")).toBe(false);
    expect(journalQuery(options({ range: { mode: "historical", minutes: 1, start: "yesterday", end: "" } }), NOW)).toEqual({
      error: 'Start time "yesterday" is not a valid date.',
    });
    expect(journalQuery(options({ range: { mode: "historical", minutes: 1, start: "2026-10-02T00:00:00Z", end: "2026-10-01T00:00:00Z" } }), NOW)).toEqual({
      error: "End time is before start time.",
    });
  });

  it("returns null when no event type is selected", () => {
    expect(journalQuery(options({ eventTypes: { active: false, clear: false, ack: false, shelve: false, unshelve: false } }), NOW)).toBeNull();
  });
});

describe("parseJournalPage and cells", () => {
  const body = {
    events: [
      { id: 7, eventId: "e1", source: "prov:default:/tag:tank/level:/alm:High", displayPath: "Tank/High", priority: "High", priorityCode: 3, eventType: "active", eventTypeCode: 0, eventFlags: 0, eventTime: "2026-10-28T10:00:00Z", actor: null, data: { eventValue: 150.5, setpointA: 100 } },
      { id: 8, eventId: "e1", source: "prov:default:/tag:tank/level:/alm:High", displayPath: "Tank/High", priority: "High", priorityCode: 3, eventType: "ack", eventTime: "2026-10-28T10:01:00Z", actor: "admin", data: { ackUser: "admin", ackNotes: "checked" } },
      { id: 9, eventId: "e1", source: "prov:default:/tag:tank/level:/alm:High", displayPath: "Tank/High", priority: "High", eventType: "shelve", eventTime: "2026-10-28T10:02:00Z", actor: "op", data: { actor: "op", shelveExpiry: Date.parse("2026-10-28T10:17:00Z") } },
      { eventId: "no-id" },
      null,
    ],
    total: 42,
    offset: 0,
    limit: 3,
  };

  it("parses rows, skips malformed ones and keeps paging totals", () => {
    const page = parseJournalPage(body);
    expect(page.events.map((event) => event.id)).toEqual([7, 8, 9]);
    expect(page.total).toBe(42);
    expect(page.events[2]!.priorityCode).toBe(3);
    expect(parseJournalPage(null)).toEqual({ events: [], total: 0, offset: 0, limit: 0 });
  });

  it("formats each column", () => {
    const [active, ack, shelve] = parseJournalPage(body).events;
    expect(journalCellText(active!, "eventTime", "en-US", "UTC")).toBe("10/28/26, 10:00:00 AM");
    expect(journalCellText(active!, "eventType", "en-US")).toBe("Active");
    expect(journalCellText(active!, "eventValue", "en-US")).toBe("150.5");
    expect(journalCellText(active!, "label", "en-US")).toBe("High");
    expect(journalCellText(ack!, "eventType", "en-US")).toBe("Acknowledged");
    expect(journalCellText(ack!, "actor", "en-US")).toBe("admin");
    expect(journalCellText(ack!, "ackNotes", "en-US")).toBe("checked");
    expect(journalCellText(shelve!, "eventType", "en-US")).toBe("Shelved");
    expect(journalCellText(shelve!, "shelvedUntil", "en-US", "UTC")).toBe("10/28/26, 10:17:00 AM");
    expect(journalCellText(shelve!, "eventValue", "en-US")).toBe("");
  });

  it("searches the loaded page across columns", () => {
    const events = parseJournalPage(body).events;
    expect(searchJournal(events, "CHECKED", "en-US").map((event) => event.id)).toEqual([8]);
    expect(searchJournal(events, "shelved", "en-US").map((event) => event.id)).toEqual([9]);
    expect(searchJournal(events, "  ", "en-US")).toHaveLength(3);
  });

  it("parses columns, priorities and labels", () => {
    expect(parseJournalColumns("actor, bogus, eventTime, actor")).toEqual(["actor", "eventTime"]);
    expect(parseJournalColumns("")).toEqual(DEFAULT_JOURNAL_COLUMNS);
    expect(parsePriority("high", "Low")).toBe("High");
    expect(parsePriority("urgent", "Low")).toBe("Low");
    expect(journalLabel({ source: "custom", displayPath: "Area/Pump/Trip" })).toBe("Trip");
  });
});
