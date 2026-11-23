import { useCallback, useEffect, useState } from "react";
import type { ApiClient } from "./api";

/**
 * Live alarms on the Alarming page (UI audit): what is active or waiting for acknowledgement right now, with an
 * Acknowledge button, so an administrator can see a tag alarm work without building an HMI screen first.
 */

export interface LiveAlarm {
  eventId: string;
  name: string;
  source: string;
  displayPath: string;
  priority: string;
  state: string;
  active: boolean;
  acknowledged: boolean;
  activeTime: string;
}

const POLL_MS = 2_000;

export function parseLiveAlarms(body: unknown): LiveAlarm[] {
  const list = body && typeof body === "object" && Array.isArray((body as { alarms?: unknown }).alarms) ? (body as { alarms: unknown[] }).alarms : [];
  return list
    .filter((a): a is Record<string, unknown> => !!a && typeof a === "object")
    .map((a) => ({
      eventId: String(a.eventId ?? ""),
      name: String(a.name ?? ""),
      source: String(a.source ?? ""),
      displayPath: String(a.displayPath ?? ""),
      priority: String(a.priority ?? ""),
      state: String(a.state ?? ""),
      active: a.active === true,
      acknowledged: a.acknowledged === true,
      activeTime: String(a.activeTime ?? ""),
    }))
    .filter((a) => a.eventId !== "" && (a.active || !a.acknowledged));
}

export function ActiveAlarmsPanel({ client, canAck }: { client?: ApiClient; canAck: boolean }) {
  const [alarms, setAlarms] = useState<LiveAlarm[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!client) return;
    try {
      const res = await client.request("GET", "/api/v1/alarms");
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setAlarms(parseLiveAlarms(await res.json()));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [client]);

  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), POLL_MS);
    return () => clearInterval(timer);
  }, [load]);

  async function ack(alarm: LiveAlarm) {
    if (!client) return;
    const res = await client.request("POST", "/api/v1/alarms/ack", { eventIds: [alarm.eventId] });
    if (!res.ok) setError(`Could not acknowledge ${alarm.name} (HTTP ${res.status})`);
    await load();
  }

  return (
    <section aria-label="Active alarms panel">
      <h2>Active alarms</h2>
      {error ? <p role="alert">Could not load alarms: {error}</p> : null}
      <table className="data-table" aria-label="Active alarms">
        <thead>
          <tr>
            <th>Since</th>
            <th>Alarm</th>
            <th>Source</th>
            <th>Priority</th>
            <th>State</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {alarms && alarms.length === 0 ? (
            <tr>
              <td colSpan={6}>No active alarms.</td>
            </tr>
          ) : null}
          {(alarms ?? []).map((a) => (
            <tr key={a.eventId}>
              <td>{a.activeTime ? new Date(a.activeTime).toLocaleString() : ""}</td>
              <td>{a.name}</td>
              <td>{a.displayPath || a.source}</td>
              <td>{a.priority}</td>
              <td>{a.acknowledged ? "Acknowledged" : a.active ? "Active, not acknowledged" : "Cleared, not acknowledged"}</td>
              <td>
                {canAck && !a.acknowledged ? (
                  <button type="button" onClick={() => void ack(a)}>
                    Acknowledge {a.name}
                  </button>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
