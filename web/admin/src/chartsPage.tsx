import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import { ApiError } from "./api";
import { buildChart, chartProblem, emptyChart, type ChartDraft } from "./chartModel";
import type { AdminPageProps } from "./pages";

const MODULE = "sfc";
const KIND = "Chart";

/**
 * Chart design form and live monitor. A chart is the right tool when the work is a sequence of steps
 * and transitions. A script is the right tool when it is one action.
 */
export function ChartsPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "tags.write");
  const canConfig = allows(session, "gateway.write");
  const [charts, setCharts] = useState<Array<{ name: string; executionMode: string; elements: string[]; valid: boolean }>>([]);
  const [instances, setInstances] = useState<Array<{ id: string; chart: string; state: string; activeSteps: string[] }>>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [view, setView] = useState<"design" | "monitor">("design");
  const [draft, setDraft] = useState<ChartDraft>(emptyChart());
  const [name, setName] = useState("");
  const [jsonMode, setJsonMode] = useState(false);
  const [jsonText, setJsonText] = useState("{}");

  async function load() {
    if (!client) return;
    const [nextCharts, nextInstances] = await Promise.all([client.sfcCharts(), client.sfcInstances()]);
    setCharts(nextCharts);
    setInstances(nextInstances);
  }

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    load().catch((err: unknown) => {
      if (!cancelled) setError(errorMessage(err, "Could not load charts"));
    });
    const timer = window.setInterval(() => {
      load().catch(() => {});
    }, 1000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [client]);

  function set<K extends keyof ChartDraft>(patch: Pick<ChartDraft, K>) {
    setDraft((current) => ({ ...current, ...patch }));
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!client) return;
    setError(null);
    setNotice(null);
    const problem = jsonMode ? null : chartProblem(name.trim(), draft);
    if (problem) {
      setError(problem);
      return;
    }
    try {
      const spec = jsonMode ? (JSON.parse(jsonText) as Record<string, unknown>) : buildChart(draft);
      await client.createResource(MODULE, "gateway", KIND, name.trim(), spec);
      setNotice(`Saved ${name.trim()}.`);
      setName("");
      setDraft(emptyChart());
      setJsonMode(false);
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? errorMessage(err, "Could not save the chart") : err instanceof Error ? err.message : "Could not save the chart");
    }
  }

  async function remove(chartName: string) {
    if (!client) return;
    setError(null);
    try {
      const items = await client.listResources(MODULE, "gateway", KIND);
      const found = items.find((item) => item.metadata.name === chartName);
      if (!found) throw new Error("That chart is no longer on the gateway.");
      await client.deleteResource(MODULE, "gateway", KIND, chartName, found.metadata.version);
      setNotice(`Deleted ${chartName}.`);
      await load();
    } catch (err) {
      setError(errorMessage(err, "Could not delete the chart"));
    }
  }

  async function control(id: string, action: "pause" | "resume" | "cancel") {
    if (!client) return;
    setError(null);
    try {
      await client.sfcControl(id, action);
      setNotice(action === "pause" ? "Paused." : action === "resume" ? "Resumed." : "Canceled.");
      await load();
    } catch (err) {
      setError(errorMessage(err, "Could not change the running chart"));
    }
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>Use a chart when the work is a sequence of steps and transitions. Use a script when it is one action.</p>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      <div role="tablist" aria-label="Chart views">
        <button type="button" role="tab" aria-selected={view === "design"} onClick={() => setView("design")}>
          Design
        </button>
        <button type="button" role="tab" aria-selected={view === "monitor"} onClick={() => setView("monitor")}>
          Monitor
        </button>
      </div>
      {view === "design" ? (
        <>
          {canConfig ? (
            <form aria-label="New chart" onSubmit={(event) => void save(event)}>
              <label>
                Chart name
                <input aria-label="Chart name" value={name} onChange={(event) => setName(event.target.value)} />
              </label>
              <label>
                Run mode
                <select aria-label="Run mode" value={draft.executionMode} disabled={jsonMode} onChange={(event) => set({ executionMode: event.target.value as ChartDraft["executionMode"] })}>
                  <option value="callable">Callable</option>
                  <option value="runAlways">Run always</option>
                  <option value="disabled">Disabled</option>
                </select>
              </label>
              <label>
                Parameters
                <input aria-label="Parameters" value={draft.parameters} disabled={jsonMode} onChange={(event) => set({ parameters: event.target.value })} />
              </label>
              <label>
                Variables
                <input aria-label="Variables" value={draft.variables} disabled={jsonMode} onChange={(event) => set({ variables: event.target.value })} />
              </label>
              <label>
                Action script
                <textarea aria-label="Action script" value={draft.actionScript} disabled={jsonMode} onChange={(event) => set({ actionScript: event.target.value })} rows={3} />
              </label>
              <label>
                Enclosed chart
                <input aria-label="Enclosed chart" value={draft.enclosingChart} disabled={jsonMode} onChange={(event) => set({ enclosingChart: event.target.value })} />
              </label>
              <label>
                Parallel left
                <textarea aria-label="Parallel left" value={draft.parallelLeft} disabled={jsonMode} onChange={(event) => set({ parallelLeft: event.target.value })} rows={2} />
              </label>
              <label>
                Parallel right
                <textarea aria-label="Parallel right" value={draft.parallelRight} disabled={jsonMode} onChange={(event) => set({ parallelRight: event.target.value })} rows={2} />
              </label>
              <label>
                Transition
                <input aria-label="Transition" value={draft.expression} disabled={jsonMode} onChange={(event) => set({ expression: event.target.value })} />
              </label>
              <label>
                <input type="checkbox" checked={draft.loop} disabled={jsonMode} onChange={(event) => set({ loop: event.target.checked })} />
                Jump back to the start
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={jsonMode}
                  onChange={(event) => {
                    const on = event.target.checked;
                    if (on) setJsonText(JSON.stringify(buildChart(draft), null, 2));
                    setJsonMode(on);
                  }}
                />
                Edit as JSON
              </label>
              {jsonMode ? <textarea aria-label="Chart JSON" value={jsonText} onChange={(event) => setJsonText(event.target.value)} rows={12} /> : null}
              <button type="submit">Save chart</button>
            </form>
          ) : null}
          <table className="data-table" aria-label="Charts">
            <thead>
              <tr>
                <th>Chart</th>
                <th>Mode</th>
                <th>Elements</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {charts.map((chart) => (
                <tr key={chart.name} data-valid={chart.valid ? "yes" : "no"}>
                  <td>{chart.name}</td>
                  <td>{chart.executionMode}</td>
                  <td>{chart.elements.join(", ")}</td>
                  <td>
                    {canWrite ? (
                      <button
                        type="button"
                        onClick={() => {
                          void client?.sfcStart(chart.name).then(() => load()).catch((err: unknown) => setError(errorMessage(err, "Could not start the chart")));
                        }}
                      >
                        Start {chart.name}
                      </button>
                    ) : null}
                    {canConfig ? (
                      <button type="button" onClick={() => void remove(chart.name)}>
                        Delete {chart.name}
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      ) : (
        <table className="data-table" aria-label="Running charts">
          <thead>
            <tr>
              <th>Chart</th>
              <th>State</th>
              <th>Active steps</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {instances.map((instance) => (
              <tr key={instance.id}>
                <td>{instance.chart}</td>
                <td>{instance.state}</td>
                <td>
                  {instance.activeSteps.map((step) => (
                    <span key={step} data-active="true">
                      {step}
                    </span>
                  ))}
                </td>
                <td>
                  {canWrite ? (
                    <>
                      <button type="button" onClick={() => void control(instance.id, "pause")}>Pause</button>
                      <button type="button" onClick={() => void control(instance.id, "resume")}>Resume</button>
                      <button type="button" onClick={() => void control(instance.id, "cancel")}>Cancel</button>
                    </>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </article>
  );
}
