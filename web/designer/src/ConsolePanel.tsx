import { useState, type FormEvent } from "react";
import { GatewayError, type GatewayClient } from "./gatewayClient";

/** Designer Python console. The gateway runs the source; this panel shows the printed output and the result. */
export function ConsolePanel({ client }: { client: GatewayClient }) {
  const [source, setSource] = useState("print(1 + 1)\n_result = 2\n");
  const [output, setOutput] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const result = await client.runConsole(source);
      const lines = [result.output.trim(), result.ok ? `result: ${shown(result.value)}` : (result.error ?? "script failed")].filter((line) => line.length > 0);
      setOutput(lines.join("\n"));
    } catch (caught) {
      setOutput("");
      setError(caught instanceof GatewayError ? caught.message : caught instanceof Error ? caught.message : String(caught));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="oip-console" data-testid="designer-console" aria-label="Python console" onSubmit={run}>
      <p className="oip-binding-muted">Runs on the gateway as the signed-in designer. Assign `_result` and use `print` for output.</p>
      <ul data-testid="scope-hints" aria-label="Functions available in the designer">
        {DESIGNER_HINTS.map((name) => (
          <li key={name}>{name}</li>
        ))}
      </ul>
      <textarea aria-label="Python" value={source} onChange={(event) => setSource(event.target.value)} spellCheck={false} rows={8} />
      <button type="submit" disabled={busy}>
        {busy ? "Running" : "Run"}
      </button>
      {error ? <p role="alert">{error}</p> : null}
      <pre data-testid="console-output">{output}</pre>
    </form>
  );
}

/** Names returned by `system.util.scopeHints("designer")` in the gateway prelude. */
const DESIGNER_HINTS = [
  "system.tag.readBlocking",
  "system.config.putResource",
  "system.historian.queryRaw",
  "system.device.listDevices",
  "system.opc.read",
  "system.opcua.listConnections",
  "system.user.listUsers",
];

function shown(value: unknown): string {
  if (value === undefined) return "";
  if (typeof value === "string") return value;
  return JSON.stringify(value);
}
