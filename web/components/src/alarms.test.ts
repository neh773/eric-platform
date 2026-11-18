import { describe, expect, it, vi } from "vitest";
import {
  DEFAULT_PRIORITIES,
  DEFAULT_STATES,
  alarmApi,
  alarmsQuery,
  cellText,
  filterAlarms,
  nextAlarmSort,
  parseAlarms,
  parseColumns,
  parseShelvingTimes,
  sortAlarms,
  stateKey,
  type AlarmEvent,
} from "./alarms";
import { GatewayClient } from "./gateway";

/**
 * Alarm status table logic.
 */

function alarm(overrides: Partial<AlarmEvent> & { eventId: string }): AlarmEvent {
  const active = overrides.active ?? true;
  const acknowledged = overrides.acknowledged ?? false;
  const labels = ["Cleared and Unacknowledged", "Cleared and Acknowledged", "Active and Unacknowledged", "Active and Acknowledged"];
  const code = (active ? 2 : 0) + (acknowledged ? 1 : 0);
  return {
    source: `prov:default:/tag:${overrides.eventId}:/alm:High`,
    name: "High",
    displayPath: overrides.eventId,
    tagPath: overrides.eventId,
    priority: "High",
    priorityCode: 3,
    value: 1,
    activeTime: "2026-10-27T10:00:00Z",
    clearTime: null,
    ackTime: null,
    ackUser: null,
    ackNotes: null,
    notes: null,
    ...overrides,
    active,
    acknowledged,
    state: labels[code]!,
    stateCode: code,
  };
}

const sample = [
  alarm({ eventId: "a", displayPath: "Tank A level", priority: "Low", priorityCode: 1, activeTime: "2026-10-27T10:00:00Z" }),
  alarm({ eventId: "b", displayPath: "Tank B level", priority: "Critical", priorityCode: 4, activeTime: "2026-10-27T10:05:00Z", acknowledged: true, ackTime: "2026-10-27T10:06:00Z", ackUser: "op" }),
  alarm({ eventId: "c", displayPath: "Pump trip", priority: "Medium", priorityCode: 2, activeTime: "2026-10-27T09:00:00Z", active: false }),
  alarm({ eventId: "d", displayPath: "Old valve", priority: "High", priorityCode: 3, active: false, acknowledged: true }),
  alarm({ eventId: "e", displayPath: "Heartbeat", priority: "Diagnostic", priorityCode: 0 }),
];

describe("alarm table logic", () => {
  it("parses the gateway response and skips rows without an event id", () => {
    const parsed = parseAlarms({
      alarms: [
        {
          eventId: "x1",
          source: "prov:default:/tag:tank/level:/alm:High",
          name: "High",
          displayPath: "tank/level/High",
          priority: "High",
          priorityCode: 3,
          state: "Active and Unacknowledged",
          stateCode: 2,
          active: true,
          acknowledged: false,
          value: 150,
          activeTime: "2026-10-27T10:00:00Z",
          clearTime: null,
          ackTime: null,
          ackUser: null,
        },
        { source: "no id" },
        "junk",
      ],
    });
    expect(parsed).toHaveLength(1);
    expect(parsed[0]).toMatchObject({ eventId: "x1", name: "High", stateCode: 2, value: 150, ackTime: null });
    expect(parseAlarms(null)).toEqual([]);
    expect(parseAlarms({ alarms: "nope" })).toEqual([]);
  });

  it("maps active and acknowledged flags to the four states", () => {
    expect(stateKey({ active: true, acknowledged: false })).toBe("activeUnacked");
    expect(stateKey({ active: true, acknowledged: true })).toBe("activeAcked");
    expect(stateKey({ active: false, acknowledged: false })).toBe("clearedUnacked");
    expect(stateKey({ active: false, acknowledged: true })).toBe("clearedAcked");
  });

  it("filters by the documented default states and priorities", () => {
    const shown = filterAlarms(sample, { states: DEFAULT_STATES, priorities: DEFAULT_PRIORITIES, search: "" });
    // Cleared-and-acknowledged (d) and Diagnostic (e) are off by default.
    expect(shown.map((event) => event.eventId)).toEqual(["a", "b", "c"]);
    const all = filterAlarms(sample, {
      states: { ...DEFAULT_STATES, clearedAcked: true },
      priorities: { ...DEFAULT_PRIORITIES, Diagnostic: true },
      search: "",
    });
    expect(all).toHaveLength(5);
    const onlyCritical = filterAlarms(sample, {
      states: DEFAULT_STATES,
      priorities: { Diagnostic: false, Low: false, Medium: false, High: false, Critical: true },
      search: "",
    });
    expect(onlyCritical.map((event) => event.eventId)).toEqual(["b"]);
  });

  it("searches display path, source, label, state, and ack user case-insensitively", () => {
    const options = { states: { ...DEFAULT_STATES, clearedAcked: true }, priorities: DEFAULT_PRIORITIES };
    expect(filterAlarms(sample, { ...options, search: "TANK" }).map((event) => event.eventId)).toEqual(["a", "b"]);
    expect(filterAlarms(sample, { ...options, search: "pump" }).map((event) => event.eventId)).toEqual(["c"]);
    expect(filterAlarms(sample, { ...options, search: "op" }).map((event) => event.eventId)).toEqual(["b"]);
    expect(filterAlarms(sample, { ...options, search: "cleared and ack" }).map((event) => event.eventId)).toEqual(["d"]);
  });

  it("sorts by priority, state, time, and text in both directions", () => {
    const ids = (events: AlarmEvent[]) => events.map((event) => event.eventId);
    expect(ids(sortAlarms(sample, { column: "priority", direction: "desc" }))).toEqual(["b", "d", "c", "a", "e"]);
    expect(ids(sortAlarms(sample, { column: "priority", direction: "asc" }))).toEqual(["e", "a", "c", "d", "b"]);
    expect(ids(sortAlarms(sample, { column: "activeTime", direction: "desc" })).slice(0, 2)).toEqual(["b", "a"]);
    expect(ids(sortAlarms(sample, { column: "displayPath", direction: "asc" }))).toEqual(["e", "d", "c", "a", "b"]);
    const byState = sortAlarms(sample, { column: "state", direction: "desc" });
    expect(byState[0]!.state).toBe("Active and Acknowledged");
    expect(byState[byState.length - 1]!.state).toBe("Cleared and Unacknowledged");
  });

  it("starts state and priority descending and toggles direction on a second click", () => {
    const start = { column: "activeTime" as const, direction: "desc" as const };
    expect(nextAlarmSort(start, "priority")).toEqual({ column: "priority", direction: "desc" });
    expect(nextAlarmSort(start, "state")).toEqual({ column: "state", direction: "desc" });
    expect(nextAlarmSort(start, "displayPath")).toEqual({ column: "displayPath", direction: "asc" });
    expect(nextAlarmSort({ column: "displayPath", direction: "asc" }, "displayPath")).toEqual({ column: "displayPath", direction: "desc" });
  });

  it("renders cells, columns, and shelving times from inspector strings", () => {
    const event = sample[1]!;
    expect(cellText(event, "label", "en-US")).toBe("High");
    expect(cellText(event, "ackUser", "en-US")).toBe("op");
    expect(cellText(event, "ackTime", "en-US", "UTC")).toBe("10/27/26, 10:06:00 AM");
    expect(cellText(sample[0]!, "ackTime", "en-US")).toBe("");
    expect(parseColumns("state, label, bogus, state")).toEqual(["state", "label"]);
    expect(parseColumns("")).toContain("activeTime");
    expect(parseShelvingTimes("60, 5, x, -1, 5")).toEqual([5, 60]);
    expect(parseShelvingTimes("nope")).toEqual([5, 15, 30, 60]);
    expect(alarmsQuery("", "")).toBe("/api/v1/alarms");
    expect(alarmsQuery("prov:default:*", " tank* ")).toBe("/api/v1/alarms?source=prov%3Adefault%3A*&displayPath=tank*");
  });

  it("sends ack, shelve, and unshelve bodies with the CSRF header", async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    const fetch = vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      if (url === "/api/v1/auth/session") return Response.json({ principal: "op", csrfToken: "tok" });
      return Response.json({ ok: true });
    });
    const client = new GatewayClient({ fetch });
    await alarmApi.acknowledge(client, ["e1"], "  checked  ");
    await alarmApi.acknowledge(client, ["e2"], "");
    await alarmApi.shelve(client, ["src"], 15);
    await alarmApi.unshelve(client, ["src"]);
    const posts = calls.filter((call) => call.init?.method === "POST");
    expect(posts.map((call) => [call.url, JSON.parse(String(call.init!.body))])).toEqual([
      ["/api/v1/alarms/ack", { eventIds: ["e1"], notes: "checked" }],
      ["/api/v1/alarms/ack", { eventIds: ["e2"] }],
      ["/api/v1/alarms/shelve", { sources: ["src"], durationSeconds: 900 }],
      ["/api/v1/alarms/unshelve", { sources: ["src"] }],
    ]);
    for (const post of posts) expect((post.init!.headers as Record<string, string>)["X-CSRF-Token"]).toBe("tok");
    // One session fetch: the token is cached in memory.
    expect(calls.filter((call) => call.url === "/api/v1/auth/session")).toHaveLength(1);
  });

  it("refreshes a rejected CSRF token once and surfaces other 403s", async () => {
    let token = 0;
    const fetch = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === "/api/v1/auth/session") return Response.json({ csrfToken: `t${++token}` });
      const sent = (init?.headers as Record<string, string>)["X-CSRF-Token"];
      if (url === "/api/v1/alarms/ack" && sent === "t1") {
        return Response.json({ type: "urn:oip:problem:csrf", detail: "CSRF token missing or rejected" }, { status: 403 });
      }
      if (url === "/api/v1/alarms/shelve") {
        return Response.json({ type: "urn:oip:problem:forbidden", detail: "missing permission tags.write" }, { status: 403 });
      }
      return Response.json({});
    });
    const client = new GatewayClient({ fetch });
    await alarmApi.acknowledge(client, ["e1"], "");
    expect(token).toBe(2);
    await expect(alarmApi.shelve(client, ["s"], 5)).rejects.toMatchObject({ status: 403, problemType: "forbidden", message: "403 missing permission tags.write" });
    expect(token).toBe(2);
  });
});
