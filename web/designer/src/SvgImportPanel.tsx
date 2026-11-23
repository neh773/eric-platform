import { useId, useMemo, useState, type ChangeEvent } from "react";
import { importSvg, SVG_IMPORT_MAX_BYTES, type SvgImport } from "@oip/components";

/**
 * Import an SVG as a component (HMV-151): paste markup or choose a `.svg` file. The markup is cleaned (scripts, event
 * handlers, foreign content and external references removed) and the groups, shapes and texts found are listed before
 * the component is added to the view.
 */
export function SvgImportPanel({ onAdd }: { onAdd: (imported: Extract<SvgImport, { ok: true }>, name: string) => void }) {
  const [markup, setMarkup] = useState("");
  const [name, setName] = useState("SVG component");
  const [fileError, setFileError] = useState<string | null>(null);
  const textId = useId();
  const result = useMemo(() => (markup.trim() === "" ? null : importSvg(document, markup)), [markup]);

  function onFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    setFileError(null);
    if (file.size > SVG_IMPORT_MAX_BYTES) {
      setFileError("The SVG is larger than 512 KB");
      return;
    }
    void file.text().then(
      (text) => {
        setMarkup(text);
        setName(file.name.replace(/\.svg$/i, "") || "SVG component");
      },
      () => setFileError("The file could not be read"),
    );
  }

  return (
    <section className="oip-library oip-svg-import" aria-label="Import SVG" data-testid="svg-import">
      <p>Import SVG</p>
      <label className="oip-schema-field">
        <span>SVG file</span>
        <input type="file" accept=".svg,image/svg+xml" aria-label="SVG file" onChange={onFile} />
      </label>
      <label className="oip-schema-field" htmlFor={textId}>
        <span>Or paste SVG markup</span>
      </label>
      <textarea id={textId} aria-label="SVG markup" rows={5} spellCheck={false} value={markup} onChange={(event) => setMarkup(event.target.value)} />
      <label className="oip-schema-field">
        <span>Component name</span>
        <input aria-label="SVG component name" type="text" value={name} onChange={(event) => setName(event.target.value)} />
      </label>
      {fileError ? (
        <p className="oip-binding-error" role="alert">
          {fileError}
        </p>
      ) : null}
      {result && !result.ok ? (
        <p className="oip-binding-error" role="alert">
          {result.reason}
        </p>
      ) : null}
      {result?.ok ? (
        <div role="status" className="oip-svg-summary">
          <p>
            {result.elements.length} parts found: {count(result, "group")} groups, {count(result, "shape")} shapes, {count(result, "text")} texts.
          </p>
          {result.removed.length > 0 ? <p className="oip-binding-muted">Removed for safety: {[...new Set(result.removed)].join(", ")}.</p> : null}
        </div>
      ) : null}
      {!result && !fileError ? (
        <p className="oip-binding-muted" data-testid="svg-import-hint">
          Choose an SVG file or paste SVG markup first.
        </p>
      ) : null}
      <button
        type="button"
        disabled={!result?.ok}
        title={!result?.ok ? (result && !result.ok ? result.reason : "Choose an SVG file or paste SVG markup first.") : undefined}
        onClick={() => result?.ok && onAdd(result, name.trim() || "SVG component")}
      >
        Add SVG component
      </button>
    </section>
  );
}

function count(result: Extract<SvgImport, { ok: true }>, kind: "group" | "shape" | "text"): number {
  return result.elements.filter((entry) => entry.kind === kind).length;
}
