import { useState, type FormEvent } from "react";
import type { GatewayClient } from "./gatewayClient";
import { AGGREGATE_MODES, buildRequests, toRows, type AggregateMode, type HistoryRow } from "./historyQuery";

/**
 * HIS-003: tag-history query panel. Tag paths, start and end may hold `{name}` tokens bound to named values,
 * so one saved query shape can be rerun with different values. Runs against the gateway's /api/v1/history routes.
 */
export function HistoryQueryTool({ client, now = () => Date.now() }: { client: GatewayClient; now?: () => number }) {
  const [tags, setTags] = useState("");
  const [start, setStart] = useState("last 1 h");
  const [end, setEnd] = useState("now");
  const [mode, setMode] = useState<AggregateMode>("Average");
  const [interval, setIntervalSeconds] = useState(60);
  const [params, setParams] = useState<Array<{ name: string; value: string }>>([]);
  const [rows, setRows] = useState<HistoryRow[]>([]);
  const [error, setError] = useState<string | null>(null);

  async function run(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const map = Object.fromEntries(params.filter((p) => p.name).map((p) => [p.name, p.value]));
      const requests = buildRequests({ tagPaths: tags.split("\n"), start, end, mode, intervalSeconds: interval }, map, now());
      if (requests.length === 0) throw new Error("Enter at least one tag path");
      const all: HistoryRow[] = [];
      for (const r of requests) all.push(...toRows(r.tagPath, await client.historyQuery(r.path)));
      setRows(all);
    } catch (err) {
      setRows([]);
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  return (
    <form aria-label="Tag history query" onSubmit={(event) => void run(event)}>
      <label>
        Tag paths (one per line)
        <textarea aria-label="History tag paths" rows={3} value={tags} onChange={(e) => setTags(e.target.value)} />
      </label>
      <label>
        Start
        <input aria-label="History start" value={start} onChange={(e) => setStart(e.target.value)} />
      </label>
      <label>
        End
        <input aria-label="History end" value={end} onChange={(e) => setEnd(e.target.value)} />
      </label>
      <label>
        Aggregation mode
        <select aria-label="History aggregation mode" value={mode} onChange={(e) => setMode(e.target.value as AggregateMode)}>
          <option value="Raw">Raw</option>
          {AGGREGATE_MODES.map((m) => (
            <option key={m} value={m}>
              {m}
            </option>
          ))}
        </select>
      </label>
      <label>
        Interval (seconds)
        <input aria-label="History interval seconds" type="number" min={1} value={interval} onChange={(e) => setIntervalSeconds(Number(e.target.value))} />
      </label>
      <fieldset>
        <legend>Parameters</legend>
        {params.map((p, i) => (
          <div key={i}>
            <input aria-label={`Parameter ${i + 1} name`} value={p.name} onChange={(e) => setParams(params.map((q, j) => (j === i ? { ...q, name: e.target.value } : q)))} />
            <input aria-label={`Parameter ${i + 1} value`} value={p.value} onChange={(e) => setParams(params.map((q, j) => (j === i ? { ...q, value: e.target.value } : q)))} />
          </div>
        ))}
        <button type="button" onClick={() => setParams([...params, { name: "", value: "" }])}>
          Add parameter
        </button>
      </fieldset>
      <button type="submit">Run history query</button>
      {error ? <p role="alert">{error}</p> : null}
      {rows.length > 0 ? (
        <table aria-label="History result">
          <thead>
            <tr>
              <th>Tag</th>
              <th>Time</th>
              <th>Value</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i}>
                <td>{r.tagPath}</td>
                <td>{r.time}</td>
                <td>{r.value}</td>
                <td>{r.extra}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </form>
  );
}
