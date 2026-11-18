import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import type { AdminPageProps } from "./pages";
import { PlcWindowsBanner } from "./plcBanner";

export interface PlcResourceStatus {
  physicalCores: number;
  memoryBytes: number;
  threads: Array<{ id: number; coreId: number; siblings: number[] }>;
  defaults: { plcCores: number; plcRamGb: number; disableSmt: boolean };
  state: string;
  cmdline: string;
  helperConfigured: boolean;
  pending: { cmdline?: string; plcCpus?: number[] } | null;
  platform?: string;
  windowsNote?: string;
}

export interface PlcPreview {
  ok?: boolean;
  errors?: string[];
  cmdline?: string;
  state?: string;
  applied?: boolean;
  command?: string;
  helperOutput?: string;
  rebootRequired?: boolean;
  files?: Record<string, string>;
}

/**
 * PLC / ERIC resource split. The page shows the machine and a dry run.
 * Applying calls the root helper when one is configured, and otherwise shows the command to run.
 */
export function PlcResourcesPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  const [status, setStatus] = useState<PlcResourceStatus | null>(null);
  const [cores, setCores] = useState(1);
  const [ram, setRam] = useState(1);
  const [smt, setSmt] = useState(true);
  const [preview, setPreview] = useState<PlcPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hostOs, setHostOs] = useState<string | null>(null);

  useEffect(() => {
    if (!client) return undefined;
    let live = true;
    void client.platform().then((body) => setHostOs(body.os)).catch(() => setHostOs(null));
    client.plcResources().then((next) => {
      if (!live) return;
      setStatus(next);
      setCores(next.defaults.plcCores);
      setRam(next.defaults.plcRamGb);
      setSmt(next.defaults.disableSmt);
    }).catch((err: unknown) => {
      if (live) setError(errorMessage(err, "Could not read the processors"));
    });
    return () => {
      live = false;
    };
  }, [client]);

  async function send(action: "preview" | "apply" | "revert", event?: FormEvent) {
    event?.preventDefault();
    if (!client || !canWrite) return;
    setError(null);
    try {
      const body = action === "revert" ? await client.plcResourcesRevert() : action === "apply" ? await client.plcResourcesApply({ plcCores: cores, plcRamGb: ram, disableSmt: smt }) : await client.plcResourcesPreview({ plcCores: cores, plcRamGb: ram, disableSmt: smt });
      setPreview(body);
      setStatus(await client.plcResources());
    } catch (err) {
      setError(errorMessage(err, "Could not update the resource split"));
    }
  }

  const gib = status ? Math.floor(status.memoryBytes / (1024 * 1024 * 1024)) : 0;

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <PlcWindowsBanner os={hostOs} />
      {status?.platform === "windows" ? (
        <p>{status.windowsNote} Applying sets affinity, a locked working set, and the gateway -Xmx. Restart the gateway service and eric-plc to pick them up. Revert removes those settings.</p>
      ) : (
        <p>Choose cores and memory for the scan engine. ERIC and the operating system keep the rest. Applying the split needs a reboot.</p>
      )}
      {error ? <p role="alert">{error}</p> : null}
      {!status && !error ? <p>Loading processors.</p> : null}
      {status ? (
        <>
          <p aria-label="Detected hardware">
            {status.physicalCores} physical cores, {status.threads.length} threads, {gib} GB memory.
          </p>
          <ul aria-label="Processors">
            {status.threads.map((thread) => (
              <li key={thread.id}>CPU {thread.id}, core {thread.coreId}, siblings {thread.siblings.join(", ")}</li>
            ))}
          </ul>
          <p aria-label="Current state">
            Boot state: {status.state}. {status.pending?.cmdline ? `Pending: ${status.pending.cmdline}` : "No pending split."}
          </p>
          <form aria-label="PLC resource split" className="stack-form" onSubmit={(event) => void send("preview", event)}>
            <label>
              PLC cores
              <input aria-label="PLC cores" type="number" min={1} value={cores} onChange={(event) => setCores(Number(event.target.value))} />
            </label>
            <label>
              PLC memory (GB)
              <input aria-label="PLC memory GB" type="number" min={1} value={ram} onChange={(event) => setRam(Number(event.target.value))} />
            </label>
            <label>
              <input aria-label="Disable SMT siblings" type="checkbox" checked={smt} onChange={(event) => setSmt(event.target.checked)} />
              Disable SMT siblings of the PLC cores
            </label>
            <div className="inline-form">
              <button type="submit" disabled={!canWrite}>Preview</button>
              <button type="button" disabled={!canWrite} onClick={() => void send("apply")}>Apply</button>
              <button type="button" disabled={!canWrite} onClick={() => void send("revert")}>Revert to default</button>
            </div>
          </form>
        </>
      ) : null}
      {preview ? (
        <section aria-label="Dry run">
          {preview.errors && preview.errors.length > 0 ? <p role="alert">{preview.errors.join(" ")}</p> : null}
          {preview.cmdline ? <p>Kernel arguments: <code>{preview.cmdline}</code></p> : null}
          {preview.rebootRequired ? <p role="status">Reboot required</p> : null}
          {preview.applied ? <p>The helper applied the files.</p> : null}
          {preview.command ? <p>Root command: <code>{preview.command}</code></p> : null}
          {preview.helperOutput ? <p>{preview.helperOutput}</p> : null}
          {preview.files ? (
            <ul aria-label="Files">
              {Object.keys(preview.files).map((path) => <li key={path}>{path}</li>)}
            </ul>
          ) : null}
        </section>
      ) : null}
    </article>
  );
}
