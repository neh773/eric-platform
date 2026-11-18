import { useCallback, useEffect, useState } from "react";
import type { ApiClient } from "./api";

/**
 * Alarm pipelines currently running, and the events inside them: where each waits and until when.
 * Reads `GET /api/v1/notifications/pipelines`. Doc source: FEATURES ALA-029.
 */

export const PIPELINE_STATUS_PATH = "/api/v1/notifications/pipelines";
export const PIPELINE_STATUS_POLL_MS = 5000;

export interface PipelineCount {
  name: string;
  inFlight: number;
}

export interface PipelineEvent {
  eventId: string;
  pipeline: string;
  block: string;
  alarm: string;
  displayPath: string;
  state: string;
  waitingUntil: string | null;
}

export interface PipelineStatus {
  pipelines: PipelineCount[];
  events: PipelineEvent[];
}

const text = (value: unknown): string => (typeof value === "string" ? value : "");

export function parsePipelineStatus(body: unknown): PipelineStatus {
  const root = (body && typeof body === "object" ? body : {}) as Record<string, unknown>;
  const rows = (value: unknown): Record<string, unknown>[] =>
    Array.isArray(value) ? value.filter((row): row is Record<string, unknown> => !!row && typeof row === "object") : [];
  return {
    pipelines: rows(root.pipelines).map((row) => ({
      name: text(row.name),
      inFlight: typeof row.inFlight === "number" ? row.inFlight : 0,
    })),
    events: rows(root.events).map((row) => ({
      eventId: text(row.eventId),
      pipeline: text(row.pipeline),
      block: text(row.block),
      alarm: text(row.alarm),
      displayPath: text(row.displayPath),
      state: text(row.state),
      waitingUntil: typeof row.waitingUntil === "string" ? row.waitingUntil : null,
    })),
  };
}

export function PipelineStatusPanel({ client }: { client?: ApiClient }) {
  const [status, setStatus] = useState<PipelineStatus | null>(null);
  const [failed, setFailed] = useState(false);

  const load = useCallback(
    async (isCancelled: () => boolean) => {
      if (!client) return;
      try {
        const res = await client.request("GET", PIPELINE_STATUS_PATH);
        if (!res.ok) throw new Error(String(res.status));
        const next = parsePipelineStatus(await res.json());
        if (isCancelled()) return;
        setStatus(next);
        setFailed(false);
      } catch {
        if (!isCancelled()) setFailed(true);
      }
    },
    [client],
  );

  useEffect(() => {
    let cancelled = false;
    const isCancelled = () => cancelled;
    void load(isCancelled);
    const timer = setInterval(() => void load(isCancelled), PIPELINE_STATUS_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [load]);

  if (!client) return null;
  return (
    <section aria-labelledby="pipeline-status-heading" data-testid="pipeline-status">
      <h2 id="pipeline-status-heading">Pipelines in flight</h2>
      {failed && !status ? <p>Pipeline status is unavailable.</p> : null}
      {status && status.pipelines.length === 0 ? <p>No pipelines are running.</p> : null}
      {status && status.pipelines.length > 0 ? (
        <>
          <ul aria-label="Running pipelines">
            {status.pipelines.map((pipeline) => (
              <li key={pipeline.name}>
                {pipeline.name}: {pipeline.inFlight} in flight
              </li>
            ))}
          </ul>
          <table aria-label="Events in flight">
            <thead>
              <tr>
                <th scope="col">Alarm</th>
                <th scope="col">Pipeline</th>
                <th scope="col">Block</th>
                <th scope="col">State</th>
                <th scope="col">Waiting until</th>
              </tr>
            </thead>
            <tbody>
              {status.events.map((event) => (
                <tr key={`${event.pipeline}/${event.eventId}/${event.block}`}>
                  <td>{event.displayPath || event.alarm}</td>
                  <td>{event.pipeline}</td>
                  <td>{event.block}</td>
                  <td>{event.state}</td>
                  <td>{event.waitingUntil ? new Date(event.waitingUntil).toLocaleString() : "Running"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      ) : null}
    </section>
  );
}
