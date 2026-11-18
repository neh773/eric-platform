import { useState } from "react";
import type { ApiClient } from "../api";
import "../../../components/src/components.css";
import { ReportPreview } from "../../../components/src/report/ReportPreview";
import type { ReportResult } from "../../../components/src/report/types";
import { TextField, errorText } from "./controls";
import { layoutOf, parametersOf, stableJson, text, textToDefault, defaultToText, type Obj, type ReportSpec } from "./reportModel";

/**
 * Preview tab: runs the draft on the gateway (the real report engine, as the signed-in user) and shows the result with
 * the same layout the PDF uses. "Download PDF" and "Open PDF" come from the gateway's PDF renderer, not the browser.
 * doc source: FEATURES REP-058, REP-028.
 */
export function PreviewTab({ client, spec, onResult }: {
  client: ApiClient;
  spec: ReportSpec;
  onResult: (result: Obj | null) => void;
}) {
  const parameters = parametersOf(spec);
  const [values, setValues] = useState<Record<string, string>>({});
  const [result, setResult] = useState<ReportResult | null>(null);
  const [ranWith, setRanWith] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function supplied(): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const p of parameters) {
      const raw = values[p.name];
      if (raw === undefined || raw.trim() === "") continue;
      out[p.name] = textToDefault(p.type, raw);
    }
    return out;
  }

  async function run() {
    setBusy(true);
    setError(null);
    try {
      const body = await client.previewReport(spec, supplied());
      setResult(body as unknown as ReportResult);
      setRanWith(stableJson(spec));
      onResult(body as Obj);
    } catch (reason) {
      setError(errorText(reason, "The preview failed"));
    } finally {
      setBusy(false);
    }
  }

  async function pdf(open: boolean) {
    setBusy(true);
    setError(null);
    try {
      const blob = await client.previewReportPdf(spec, supplied());
      const url = URL.createObjectURL(blob);
      if (open) {
        window.open(url, "_blank", "noopener");
      } else {
        const link = document.createElement("a");
        link.href = url;
        link.download = `${text(spec.title, "report")}.pdf`;
        link.click();
      }
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (reason) {
      setError(errorText(reason, "The PDF could not be made"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="report-tab" role="tabpanel" aria-label="Preview">
      {parameters.length > 0 ? (
        <fieldset className="report-panel">
          <legend>Parameters</legend>
          <div className="inline-form">
            {parameters.map((p) => (
              <TextField
                key={p.name}
                label={`Preview ${p.name}`}
                value={values[p.name] ?? ""}
                placeholder={p.expression ? `= ${p.expression}` : defaultToText(p.type, p.default)}
                onChange={(v) => setValues((current) => ({ ...current, [p.name]: v }))}
              />
            ))}
          </div>
          <p className="hint">Leave a value empty to use its default or expression.</p>
        </fieldset>
      ) : null}
      <div className="inline-form">
        <button type="button" onClick={() => void run()} disabled={busy}>Run preview</button>
        <button type="button" onClick={() => void pdf(false)} disabled={busy}>Download PDF</button>
        <button type="button" onClick={() => void pdf(true)} disabled={busy}>Open PDF</button>
      </div>
      {error ? <p role="alert">{error}</p> : null}
      {result && ranWith !== stableJson(spec) ? <p className="hint" aria-live="polite">The draft changed after this preview. Run it again to see the change.</p> : null}
      {result ? (
        <div className="report-preview-host" data-testid="report-preview">
          <ReportPreview result={result} definition={{ title: text(spec.title), description: text(spec.description), layout: layoutOf(spec) as never }} />
        </div>
      ) : (
        <p className="hint">Run the preview to see the report with live data.</p>
      )}
    </div>
  );
}
