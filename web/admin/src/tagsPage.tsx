import { sectionPath } from "./sections";
import { useEffect, useMemo, useState } from "react";
import { allows, errorMessage } from "./access";
import type { TagImportPolicy, TagImportReport, UdtInstancePlan, UdtTypeInfo } from "./api";
import type { AdminPageProps } from "./pages";
import {
  buildRequest,
  expandPattern,
  fillColumn,
  nameProblems,
  parseNameList,
  patternProblems,
  requestKey,
  resizeTable,
  setCell,
  type CollisionPolicy,
  type NamePattern,
  type ParameterRow,
} from "./udtWizard";

/**
 * Tags area: the UDT multi-instance wizard (TAG-024) and tag configuration export and import (TAG-005).
 * Pick a UDT, generate instance names from a pattern or a list, fill one parameter row per instance,
 * preview (a dry run on the gateway), then create every instance in one request. If any row is
 * invalid the gateway creates nothing and the preview shows why.
 */
export function TagsPage({ section, client, session, search }: AdminPageProps) {
  const canWrite = allows(session, "tags.write");
  const [types, setTypes] = useState<UdtTypeInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [typeId, setTypeId] = useState("");
  const [folder, setFolder] = useState("");
  const [mode, setMode] = useState<"pattern" | "list">("pattern");
  const [pattern, setPattern] = useState<NamePattern>({ template: "Instance_{n}", start: 1, count: 3, step: 1, pad: 2 });
  const [nameList, setNameList] = useState("");
  const [table, setTable] = useState<ParameterRow[]>([]);
  const [policy, setPolicy] = useState<CollisionPolicy>("fail");
  const [plan, setPlan] = useState<UdtInstancePlan | null>(null);
  const [previewedKey, setPreviewedKey] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .listUdtTypes()
      .then((next) => {
        if (cancelled) return;
        setTypes(next);
        setError(null);
        // ?type= preselects a definition when the tag browser links here (C-16).
        const wanted = new URLSearchParams(search ?? "").get("type");
        const linked = next.find((t) => !t.error && t.path === wanted)?.path;
        setTypeId((current) => current || linked || next.find((t) => !t.error)?.path || "");
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setTypes(null);
        setError(errorMessage(err, "Could not list UDT definitions"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, reloadToken, search]);

  const selected = types?.find((t) => t.path === typeId) ?? null;
  const parameters = useMemo(() => selected?.parameters ?? [], [selected]);
  const names = useMemo(() => (mode === "pattern" ? expandPattern(pattern) : parseNameList(nameList)), [mode, pattern, nameList]);
  const problems = mode === "pattern" ? patternProblems(pattern) : [];
  const rowProblems = useMemo(() => nameProblems(names), [names]);

  useEffect(() => {
    setTable((current) => resizeTable(current, names.length));
  }, [names.length]);

  const body = buildRequest({
    typeId,
    folder,
    names,
    table,
    parameters: parameters.map((p) => p.name),
    onCollision: policy,
    dryRun: true,
  });
  const key = requestKey(body);
  const previewIsCurrent = plan !== null && plan.ok && previewedKey === key;
  const ready = canWrite && !!selected && !selected.error && names.length > 0 && problems.length === 0 && rowProblems.size === 0;

  async function submit(dryRun: boolean) {
    if (!client || pending || !ready) return;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      const result = await client.createUdtInstances({ ...body, dryRun });
      setPlan(result);
      setPreviewedKey(key);
      if (!dryRun && result.ok) {
        setNotice(`Created ${result.created} instance${result.created === 1 ? "" : "s"}${result.skipped ? `, skipped ${result.skipped}` : ""}.`);
        setPreviewedKey(null);
        setReloadToken((token) => token + 1);
      } else if (!result.ok) {
        setError("Nothing was created. Fix the rows marked error and preview again.");
      }
    } catch (err) {
      setPlan(null);
      setError(errorMessage(err, dryRun ? "Could not preview the instances" : "Could not create the instances"));
    } finally {
      setPending(false);
    }
  }

  const numberField = (label: string, field: keyof Omit<NamePattern, "template">) => (
    <label>
      {label}
      <input
        type="number"
        value={Number.isFinite(pattern[field]) ? pattern[field] : ""}
        onChange={(event) => setPattern({ ...pattern, [field]: Number.parseInt(event.target.value, 10) })}
      />
    </label>
  );

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <h2>Create UDT instances</h2>
      <p className="lede">
        Create many instances of one UDT at once. Preview first: the gateway checks every row, and if any row is invalid it creates
        nothing.
      </p>
      {!canWrite ? (
        <p role="status" data-testid="udt-wizard-readonly">
          Creating instances requires the tags.write permission.
        </p>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {types && types.length === 0 ? <p>No UDT definitions exist yet. Create one on the <a href={sectionPath("udt-types")}>UDT Definitions</a> page.</p> : null}
      {types && types.length > 0 ? (
        <div className="stack-form" data-testid="udt-wizard">
          <label>
            UDT definition
            <select value={typeId} onChange={(event) => setTypeId(event.target.value)}>
              {types.map((t) => (
                <option key={t.path} value={t.path} disabled={!!t.error}>
                  {t.path}
                  {t.error ? ` (${t.error})` : ""}
                </option>
              ))}
            </select>
          </label>
          <label>
            Folder
            <input value={folder} placeholder="Line1/Pumps" onChange={(event) => setFolder(event.target.value)} />
          </label>
          <fieldset>
            <legend>Names</legend>
            <label>
              <input type="radio" name="name-mode" checked={mode === "pattern"} onChange={() => setMode("pattern")} />
              Pattern
            </label>
            <label>
              <input type="radio" name="name-mode" checked={mode === "list"} onChange={() => setMode("list")} />
              List
            </label>
            {mode === "pattern" ? (
              <div className="inline-form">
                <label>
                  Name pattern
                  <input value={pattern.template} onChange={(event) => setPattern({ ...pattern, template: event.target.value })} />
                </label>
                {numberField("Start", "start")}
                {numberField("Count", "count")}
                {numberField("Step", "step")}
                {numberField("Padding", "pad")}
              </div>
            ) : (
              <label>
                Instance names
                <textarea rows={5} value={nameList} placeholder={"One name per line"} onChange={(event) => setNameList(event.target.value)} />
              </label>
            )}
            {problems.map((problem) => (
              <p key={problem} role="alert">
                {problem}
              </p>
            ))}
          </fieldset>
          <label>
            If a name already exists
            <select value={policy} onChange={(event) => setPolicy(event.target.value as CollisionPolicy)}>
              <option value="fail">Fail (create nothing)</option>
              <option value="skip">Skip that row</option>
              <option value="rename">Rename with _2, _3, ...</option>
            </select>
          </label>
          {names.length > 0 ? (
            <table className="data-table" aria-label="Instance parameters">
              <thead>
                <tr>
                  <th>Name</th>
                  {parameters.map((p) => (
                    <th key={p.name}>
                      {p.name} <small>{p.dataType}</small>
                      {table.length > 1 ? (
                        <button
                          type="button"
                          className="quiet"
                          onClick={() => setTable(fillColumn(table, p.name, table[0]?.[p.name] ?? ""))}
                        >
                          Copy row 1 to all {p.name}
                        </button>
                      ) : null}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {names.map((name, row) => (
                  <tr key={`${row}-${name}`}>
                    <td>
                      {name}
                      {rowProblems.get(row) ? <small role="alert"> {rowProblems.get(row)}</small> : null}
                    </td>
                    {parameters.map((p) => (
                      <td key={p.name}>
                        <input
                          aria-label={`${p.name} for ${name}`}
                          value={table[row]?.[p.name] ?? ""}
                          placeholder={p.default === null || p.default === undefined ? "required" : String(p.default)}
                          onChange={(event) => setTable(setCell(table, row, p.name, event.target.value))}
                        />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
          {canWrite ? (
            <div className="inline-form">
              <button type="button" disabled={pending || !ready} onClick={() => void submit(true)}>
                Preview
              </button>
              <button type="button" disabled={pending || !ready || !previewIsCurrent} onClick={() => void submit(false)}>
                {(() => {
                  const count = plan && previewIsCurrent ? plan.created : names.length;
                  return `Create ${count} ${count === 1 ? "instance" : "instances"}`;
                })()}
              </button>
            </div>
          ) : null}
        </div>
      ) : null}
      {plan ? <PlanTable plan={plan} /> : null}
      <TagExchangePanel client={client} session={session} />
    </article>
  );
}

/**
 * Tag configuration export and import as JSON (TAG-005). Export downloads the provider's document; import sends a
 * chosen file with a collision policy. An import with any invalid entry writes nothing and lists every problem.
 */
export function TagExchangePanel({ client, session }: Pick<AdminPageProps, "client" | "session">) {
  const canExport = allows(session, "gateway.read");
  const canImport = allows(session, "gateway.write");
  const [importPolicy, setImportPolicy] = useState<TagImportPolicy>("overwrite");
  const [file, setFile] = useState<File | null>(null);
  const [report, setReport] = useState<TagImportReport | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (!client || (!canExport && !canImport)) return null;

  const download = async () => {
    setBusy(true);
    setMessage(null);
    try {
      const text = await client.exportTags();
      if (typeof URL.createObjectURL === "function") {
        const url = URL.createObjectURL(new Blob([text], { type: "application/json" }));
        const link = document.createElement("a");
        link.href = url;
        link.download = "tags-default.json";
        link.click();
        URL.revokeObjectURL(url);
      }
      setMessage("Exported the default provider.");
    } catch (err: unknown) {
      setMessage(errorMessage(err, "Could not export tags"));
    } finally {
      setBusy(false);
    }
  };

  const upload = async () => {
    if (!file) return;
    setBusy(true);
    setMessage(null);
    try {
      setReport(await client.importTags(await file.text(), importPolicy));
    } catch (err: unknown) {
      setReport(null);
      setMessage(errorMessage(err, "Could not import tags"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section data-testid="tag-exchange">
      <h2>Export and import</h2>
      {canExport ? (
        <button type="button" disabled={busy} onClick={() => void download()}>
          Export tags as JSON
        </button>
      ) : null}
      {canImport ? (
        <div className="inline-form">
          <label>
            Tag file
            <input type="file" accept="application/json,.json" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
          </label>
          <label>
            If a tag already exists
            <select value={importPolicy} onChange={(event) => setImportPolicy(event.target.value as TagImportPolicy)}>
              <option value="overwrite">Overwrite it</option>
              <option value="merge">Merge the imported fields into it</option>
              <option value="ignore">Keep it</option>
            </select>
          </label>
          <button type="button" disabled={busy || !file} onClick={() => void upload()}>
            Import
          </button>
        </div>
      ) : null}
      {message ? <p role="status">{message}</p> : null}
      {report ? (
        <div data-testid="tag-import-report">
          <p role="status">
            {report.ok
              ? `Created ${report.created.length}, updated ${report.updated.length}, kept ${report.skipped.length}.`
              : "Invalid: nothing was imported."}
          </p>
          {report.failed.map((f) => (
            <p role="alert" key={`${f.resource}:${f.message}`}>
              {f.resource}: {f.message}
            </p>
          ))}
        </div>
      ) : null}
    </section>
  );
}

function PlanTable({ plan }: { plan: UdtInstancePlan }) {
  return (
    <section data-testid="udt-plan">
      <h2>{plan.applied ? "Result" : "Preview"}</h2>
      <p role="status">
        {plan.ok
          ? `${plan.applied ? "Created" : "Will create"} ${plan.created}, skip ${plan.skipped}.`
          : "Invalid: nothing will be created."}
      </p>
      {plan.requestErrors.map((message) => (
        <p role="alert" key={message}>
          {message}
        </p>
      ))}
      {plan.rows.length > 0 ? (
        <table className="data-table" aria-label="Instance plan">
          <thead>
            <tr>
              <th>#</th>
              <th>Path</th>
              <th>Status</th>
              <th>Details</th>
            </tr>
          </thead>
          <tbody>
            {plan.rows.map((row) => (
              <tr key={row.index} data-status={row.status}>
                <td>{row.index + 1}</td>
                <td>{row.path ?? row.requestedName ?? ""}</td>
                <td>{row.status}</td>
                <td>{[...row.errors, ...(row.note ? [row.note] : [])].join("; ")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </section>
  );
}
