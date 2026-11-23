import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "../access";
import type { ApiClient, ResourceDocument } from "../api";
import type { AdminPageProps } from "../pages";
import { listDevices, type DeviceInfo } from "../tags/tagClient";
import {
  ALARM_MODES,
  ALARM_PRIORITIES,
  DATA_TYPES,
  MEMBER_KINDS,
  PARAMETER_TYPES,
  cleanDefinition,
  definitionProblems,
  list,
  memberSummary,
  numText,
  obj,
  patchSpec,
  resourceNameForType,
  str,
  toNumber,
  type Spec,
} from "./udtModel";

const MODULE = "tags";
const KIND = "Udt";

/**
 * UDT Definitions (0.1.1): create, edit and delete UDT types from forms. Each member can read a device address
 * (a driver device and an address such as HR215, usually through a `{Device}` parameter), hold a memory value,
 * compute an expression, reference another tag, scale another tag (derived), or nest another UDT. Each member has
 * its scaling, engineering units and range, alarms, and history settings. Saving writes the `tags/gateway/Udt`
 * config resource; every instance re-expands at once. Import and export of definitions stay on the Tags page.
 *
 * Reading needs gateway.read; saving and deleting need tags.write and config.write, which the gateway checks.
 */
export function UdtTypesPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write") || allows(session, "tags.write");
  const [items, setItems] = useState<ResourceDocument[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** Resource name in the editor, "" for a new definition, null when closed. */
  const [editing, setEditing] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .listResources(MODULE, "gateway", KIND)
      .then((next) => {
        if (cancelled) return;
        setItems(next.sort((a, b) => str(a.spec.path).localeCompare(str(b.spec.path))));
        setError(null);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, "Could not load the UDT definitions"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, reload]);

  async function remove(doc: ResourceDocument) {
    if (!client) return;
    if (!window.confirm(`Delete the UDT definition ${str(doc.spec.path)}? Instances of it stop working until it is defined again.`)) return;
    try {
      await client.deleteResource(MODULE, "gateway", KIND, doc.metadata.name, doc.metadata.version);
      setNotice(`Deleted ${str(doc.spec.path)}.`);
      if (editing === doc.metadata.name) setEditing(null);
      setReload((n) => n + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not delete the definition"));
    }
  }

  const current = editing ? (items ?? []).find((item) => item.metadata.name === editing) ?? null : null;

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="lede">
        A UDT is a reusable tag structure: define it once with its members, alarms and history, then create one instance per
        station or machine on the Tags page.
      </p>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {items ? (
        <table className="data-table" aria-label="UDT definitions">
          <thead>
            <tr>
              <th>Type</th>
              <th>Parameters</th>
              <th>Members</th>
              <th>Inherits</th>
              {canWrite ? <th>Actions</th> : null}
            </tr>
          </thead>
          <tbody>
            {items.length === 0 ? (
              <tr>
                <td colSpan={canWrite ? 5 : 4}>No UDT definitions yet.</td>
              </tr>
            ) : null}
            {items.map((item) => (
              <tr key={item.metadata.name}>
                <td>
                  <code>{str(item.spec.path)}</code>
                </td>
                <td>{list(item.spec.parameters).map((p) => str(p.name)).join(", ") || "None"}</td>
                <td>{list(item.spec.members).length}</td>
                <td>{str(item.spec.parent) || "Nothing"}</td>
                {canWrite ? (
                  <td className="row-actions">
                    <button type="button" onClick={() => setEditing(item.metadata.name)}>
                      Edit {str(item.spec.path)}
                    </button>
                    <button type="button" onClick={() => void remove(item)}>
                      Delete {str(item.spec.path)}
                    </button>
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      ) : error ? null : (
        <p>Loading the UDT definitions.</p>
      )}
      {canWrite && items ? (
        <p>
          <button type="button" onClick={() => setEditing("")}>
            New UDT definition
          </button>
        </p>
      ) : null}
      {editing !== null && client && items ? (
        <UdtEditor
          key={editing || "new"}
          client={client}
          existing={current}
          others={items.filter((item) => item.metadata.name !== editing)}
          onSaved={(doc, created) => {
            setNotice(`${created ? "Created" : "Saved"} ${str(doc.spec.path)}. Instances update now.`);
            setEditing(null);
            setReload((n) => n + 1);
          }}
          onClose={() => setEditing(null)}
        />
      ) : null}
    </article>
  );
}

export function UdtEditor({
  client,
  existing,
  others,
  onSaved,
  onClose,
}: {
  client: ApiClient;
  existing: ResourceDocument | null;
  others: ResourceDocument[];
  onSaved: (doc: ResourceDocument, created: boolean) => void;
  onClose: () => void;
}) {
  const [spec, setSpec] = useState<Spec>(() => existing?.spec ?? { path: "Types/", parameters: [{ name: "Device", dataType: "String" }], members: [] });
  const [resourceName, setResourceName] = useState(existing?.metadata.name ?? "");
  const [nameTouched, setNameTouched] = useState(existing !== null);
  const [open, setOpen] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  const [providers, setProviders] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    listDevices(client)
      .then((next) => {
        if (!cancelled) setDevices(next);
      })
      .catch(() => undefined);
    client
      .listResources("historian", "gateway", "HistoryProvider")
      .then((next) => {
        if (!cancelled) setProviders(next.map((doc) => doc.metadata.name));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [client]);

  const params = list(spec.parameters);
  const members = list(spec.members);
  const problems = definitionProblems(spec);
  const name = nameTouched ? resourceName : resourceNameForType(str(spec.path));

  function setParams(next: Spec[]) {
    setSpec((s) => ({ ...s, parameters: next }));
  }
  function setMembers(next: Spec[]) {
    setSpec((s) => ({ ...s, members: next }));
  }
  function patchMember(index: number, patch: Spec) {
    setSpec((s) => ({ ...s, members: list(s.members).map((m, i) => (i === index ? patchSpec(m, patch) : m)) }));
  }
  function addMember() {
    const next = [...members, { name: "", type: "opc", dataType: "Double", opcServer: params.some((p) => str(p.name) === "Device") ? "{Device}" : "", scanPeriodMillis: 1000 }];
    setMembers(next);
    setOpen(next.length - 1);
  }
  function moveMember(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= members.length) return;
    const next = [...members];
    [next[index], next[target]] = [next[target]!, next[index]!];
    setMembers(next);
    setOpen(target);
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (problems.length) {
      setError(problems[0]!);
      return;
    }
    setPending(true);
    setError(null);
    try {
      const clean = cleanDefinition(spec);
      const doc = existing
        ? await client.putResource(MODULE, "gateway", KIND, existing.metadata.name, { ...existing, spec: clean })
        : await client.createResource(MODULE, "gateway", KIND, name, clean);
      onSaved(doc, existing === null);
    } catch (err) {
      setError(errorMessage(err, "Could not save the definition"));
    } finally {
      setPending(false);
    }
  }

  const deviceChoices = [
    ...params.filter((p) => str(p.name)).map((p) => ({ value: `{${str(p.name)}}`, label: `{${str(p.name)}} (parameter)` })),
    ...devices.map((d) => ({ value: d.name, label: `${d.name} (${d.driver}, ${d.state.toLowerCase() || "unknown"})` })),
  ];

  return (
    <form className="stack-form udt-editor" aria-label="UDT definition" onSubmit={save}>
      <h2>{existing ? `Edit ${str(existing.spec.path)}` : "New UDT definition"}</h2>
      <label>
        Type path
        <input
          name="udt-path"
          value={str(spec.path)}
          placeholder="Types/LiftStation_Duplex"
          onChange={(event) => setSpec((s) => ({ ...s, path: event.target.value }))}
          required
        />
      </label>
      <label>
        Resource name
        <input
          name="udt-resource"
          value={name}
          disabled={existing !== null}
          pattern="[a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?"
          onChange={(event) => {
            setNameTouched(true);
            setResourceName(event.target.value);
          }}
        />
      </label>
      <label>
        Inherits from
        <select aria-label="Inherits from" name="udt-parent" value={str(spec.parent)} onChange={(event) => setSpec((s) => patchSpec(s, { parent: event.target.value }))}>
          <option value="">Nothing</option>
          {others.map((o) => (
            <option key={o.metadata.name} value={str(o.spec.path)}>
              {str(o.spec.path)}
            </option>
          ))}
        </select>
      </label>

      <fieldset aria-label="Parameters">
        <legend>Parameters</legend>
        <p className="muted">Each instance fills these in. Use them in members as {"{Name}"}, for example the device a station reads.</p>
        <table className="data-table" aria-label="UDT parameters">
          <thead>
            <tr>
              <th>Name</th>
              <th>Data type</th>
              <th>Default</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {params.map((p, i) => (
              <tr key={i}>
                <td>
                  <input
                    aria-label={`Parameter ${i + 1} name`}
                    value={str(p.name)}
                    onChange={(event) => setParams(params.map((q, j) => (j === i ? { ...q, name: event.target.value } : q)))}
                  />
                </td>
                <td>
                  <select
                    aria-label={`Parameter ${i + 1} data type`}
                    value={str(p.dataType) || "String"}
                    onChange={(event) => setParams(params.map((q, j) => (j === i ? { ...q, dataType: event.target.value } : q)))}
                  >
                    {PARAMETER_TYPES.map((t) => (
                      <option key={t}>{t}</option>
                    ))}
                  </select>
                </td>
                <td>
                  <input
                    aria-label={`Parameter ${i + 1} default`}
                    value={str(p.default)}
                    onChange={(event) => setParams(params.map((q, j) => (j === i ? patchSpec(q, { default: event.target.value }) : q)))}
                  />
                </td>
                <td>
                  <button type="button" onClick={() => setParams(params.filter((_, j) => j !== i))}>
                    Remove parameter {str(p.name) || i + 1}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <button type="button" onClick={() => setParams([...params, { name: "", dataType: "String" }])}>
          Add parameter
        </button>
      </fieldset>

      <fieldset aria-label="Members">
        <legend>Members</legend>
        <table className="data-table" aria-label="UDT members">
          <thead>
            <tr>
              <th>Name</th>
              <th>Kind</th>
              <th>Data type</th>
              <th>Source</th>
              <th>Units</th>
              <th>Alarms</th>
              <th>History</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {members.length === 0 ? (
              <tr>
                <td colSpan={8}>No members yet. Add one below.</td>
              </tr>
            ) : null}
            {members.map((m, i) => (
              <tr key={i} aria-selected={open === i}>
                <td>{str(m.name) || <span className="muted">(unnamed)</span>}</td>
                <td>{MEMBER_KINDS.find((k) => k.id === (str(m.type) || "memory"))?.label ?? str(m.type)}</td>
                <td>{str(m.dataType) || "Double"}</td>
                <td>
                  <code>{memberSummary(m)}</code>
                </td>
                <td>{str(m.engUnit)}</td>
                <td>{list(m.alarms).length || ""}</td>
                <td>{obj(m.history).enabled === true ? str(obj(m.history).storageProvider) || "local" : ""}</td>
                <td className="row-actions">
                  <button type="button" onClick={() => setOpen(open === i ? null : i)}>
                    {open === i ? "Close" : "Edit"} member {str(m.name) || i + 1}
                  </button>
                  <button type="button" aria-label={`Move member ${str(m.name) || i + 1} up`} disabled={i === 0} onClick={() => moveMember(i, -1)}>
                    ↑
                  </button>
                  <button type="button" aria-label={`Move member ${str(m.name) || i + 1} down`} disabled={i === members.length - 1} onClick={() => moveMember(i, 1)}>
                    ↓
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setMembers(members.filter((_, j) => j !== i));
                      setOpen(null);
                    }}
                  >
                    Remove member {str(m.name) || i + 1}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <button type="button" onClick={addMember}>
          Add member
        </button>
        {open !== null && members[open] ? (
          <MemberForm
            key={open}
            member={members[open]!}
            deviceChoices={deviceChoices}
            providers={providers}
            udtTypes={others.map((o) => str(o.spec.path))}
            onPatch={(patch) => patchMember(open, patch)}
          />
        ) : null}
      </fieldset>

      {error ? <p role="alert">{error}</p> : null}
      {problems.length && !error ? <p className="muted">To save: {problems[0]}</p> : null}
      <p>
        <button type="submit" disabled={pending}>
          {existing ? "Save definition" : "Create definition"}
        </button>{" "}
        <button type="button" onClick={onClose} disabled={pending}>
          Cancel
        </button>
      </p>
    </form>
  );
}

function MemberForm({
  member,
  deviceChoices,
  providers,
  udtTypes,
  onPatch,
}: {
  member: Spec;
  deviceChoices: { value: string; label: string }[];
  providers: string[];
  udtTypes: string[];
  onPatch: (patch: Spec) => void;
}) {
  const type = str(member.type) || "memory";
  const scaling = obj(member.scaling);
  const history = obj(member.history);
  const historyOn = history.enabled === true;
  const deadband = obj(history.deadband);
  const valueDeadband = obj(member.deadband);
  const alarms = list(member.alarms);
  const label = str(member.name) || "new member";
  const deviceKnown = deviceChoices.some((c) => c.value === str(member.opcServer));

  function patchScaling(patch: Spec) {
    onPatch({ scaling: patchSpec(scaling, patch) });
  }
  function patchHistory(patch: Spec) {
    onPatch({ history: patchSpec(history, patch) });
  }
  function patchAlarm(index: number, patch: Spec) {
    onPatch({ alarms: alarms.map((a, i) => (i === index ? patchSpec(a, patch) : a)) });
  }

  return (
    <section className="member-form" aria-label={`Member ${label}`}>
      <h3>Member {label}</h3>
      <div className="form-grid">
        <label>
          Member name
          <input name="member-name" value={str(member.name)} onChange={(event) => onPatch({ name: event.target.value })} required />
        </label>
        <label>
          Kind
          <select aria-label="Kind" name="member-kind" value={type} onChange={(event) => onPatch({ type: event.target.value })}>
            {MEMBER_KINDS.map((k) => (
              <option key={k.id} value={k.id}>
                {k.label}
              </option>
            ))}
          </select>
        </label>
        {type !== "udt" ? (
          <label>
            Data type
            <select aria-label="Data type" name="member-data-type" value={str(member.dataType) || "Double"} onChange={(event) => onPatch({ dataType: event.target.value })}>
              {DATA_TYPES.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </label>
        ) : null}
        <label>
          Description
          <input name="member-documentation" value={str(member.documentation)} onChange={(event) => onPatch({ documentation: event.target.value })} />
        </label>
      </div>

      {type === "opc" ? (
        <fieldset aria-label="Device binding">
          <legend>Device binding</legend>
          <div className="form-grid">
            <label>
              Device
              <select
                aria-label="Device"
                name="member-device"
                value={deviceKnown ? str(member.opcServer) : "__other"}
                onChange={(event) => onPatch({ opcServer: event.target.value === "__other" ? "" : event.target.value })}
              >
                <option value="__other">{str(member.opcServer) && !deviceKnown ? str(member.opcServer) : "Choose a device"}</option>
                {deviceChoices.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Address
              <input
                name="member-address"
                value={str(member.opcItemPath)}
                placeholder="HR215, HR201.10, C1, ns=2;s=Pump.Speed"
                onChange={(event) => onPatch({ opcItemPath: event.target.value })}
              />
            </label>
            <label>
              Poll rate (ms)
              <input
                name="member-poll"
                type="number"
                min={10}
                value={numText(member.scanPeriodMillis)}
                onChange={(event) => onPatch({ scanPeriodMillis: toNumber(event.target.value) })}
              />
            </label>
            <label className="check-field">
              <input type="checkbox" checked={member.readOnly === true} onChange={(event) => onPatch({ readOnly: event.target.checked ? true : undefined })} />
              Read only
            </label>
          </div>
        </fieldset>
      ) : null}
      {type === "memory" ? (
        <label>
          Initial value
          <input name="member-value" value={str(member.value)} onChange={(event) => onPatch({ value: typedValue(event.target.value, str(member.dataType)) })} />
        </label>
      ) : null}
      {type === "expression" ? (
        <label>
          Expression
          <input name="member-expression" value={str(member.expression)} placeholder="{[.]PumpARunning} || {[.]PumpBRunning}" onChange={(event) => onPatch({ expression: event.target.value })} />
        </label>
      ) : null}
      {type === "reference" || type === "derived" ? (
        <label>
          Source tag path
          <input name="member-source" value={str(member.sourcePath)} placeholder="[.]RawLevel" onChange={(event) => onPatch({ sourcePath: event.target.value })} />
        </label>
      ) : null}
      {type === "derived" ? (
        <div className="form-grid">
          <label>
            Read expression
            <input name="member-read-expression" value={str(member.readExpression)} placeholder="{source} * 0.01" onChange={(event) => onPatch({ readExpression: event.target.value })} />
          </label>
          <label>
            Write expression
            <input name="member-write-expression" value={str(member.writeExpression)} placeholder="{value} * 100" onChange={(event) => onPatch({ writeExpression: event.target.value })} />
          </label>
        </div>
      ) : null}
      {type === "udt" ? (
        <label>
          Nested UDT type
          <select aria-label="Nested UDT type" name="member-type-id" value={str(member.typeId)} onChange={(event) => onPatch({ typeId: event.target.value })}>
            <option value="">Choose a type</option>
            {udtTypes.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>
      ) : null}

      {type !== "udt" ? (
        <>
          <fieldset aria-label="Scaling and engineering units">
            <legend>Scaling and engineering units</legend>
            <div className="form-grid">
              <label>
                Scaling
                <select aria-label="Scaling" name="member-scaling" value={str(scaling.mode) || "none"} onChange={(event) => patchScaling({ mode: event.target.value })}>
                  <option value="none">None</option>
                  <option value="linear">Linear</option>
                  <option value="squareRoot">Square root</option>
                </select>
              </label>
              {str(scaling.mode) === "linear" || str(scaling.mode) === "squareRoot" ? (
                <>
                  <NumberField label="Raw low" name="member-raw-low" value={scaling.rawLow} onChange={(v) => patchScaling({ rawLow: v })} />
                  <NumberField label="Raw high" name="member-raw-high" value={scaling.rawHigh} onChange={(v) => patchScaling({ rawHigh: v })} />
                  <NumberField label="Scaled low" name="member-scaled-low" value={scaling.scaledLow} onChange={(v) => patchScaling({ scaledLow: v })} />
                  <NumberField label="Scaled high" name="member-scaled-high" value={scaling.scaledHigh} onChange={(v) => patchScaling({ scaledHigh: v })} />
                  <label className="check-field">
                    <input type="checkbox" checked={scaling.clamp !== false} onChange={(event) => patchScaling({ clamp: event.target.checked })} />
                    Clamp to the scaled range
                  </label>
                </>
              ) : null}
              <label>
                Engineering units
                <input name="member-units" value={str(member.engUnit)} placeholder="%, ft, psi, A, gpm" onChange={(event) => onPatch({ engUnit: event.target.value })} />
              </label>
              <NumberField label="Engineering low" name="member-eng-low" value={member.engLow} onChange={(v) => onPatch({ engLow: v })} />
              <NumberField label="Engineering high" name="member-eng-high" value={member.engHigh} onChange={(v) => onPatch({ engHigh: v })} />
              <label>
                Value deadband
                <select aria-label="Value deadband" name="member-deadband-mode" value={str(valueDeadband.mode) || "off"} onChange={(event) => onPatch({ deadband: patchSpec(valueDeadband, { mode: event.target.value }) })}>
                  <option value="off">Off</option>
                  <option value="absolute">Absolute</option>
                  <option value="percent">Percent</option>
                </select>
              </label>
              {str(valueDeadband.mode) && valueDeadband.mode !== "off" ? (
                <NumberField label="Value deadband amount" name="member-deadband" value={valueDeadband.value} onChange={(v) => onPatch({ deadband: patchSpec(valueDeadband, { value: v }) })} />
              ) : null}
            </div>
          </fieldset>

          <fieldset aria-label="Alarms">
            <legend>Alarms</legend>
            {alarms.length === 0 ? <p className="muted">No alarms on this member.</p> : null}
            {alarms.map((alarm, i) => (
              <div className="form-grid alarm-row" key={i} role="group" aria-label={`Alarm ${str(alarm.name) || i + 1}`}>
                <label>
                  Alarm name
                  <input value={str(alarm.name)} onChange={(event) => patchAlarm(i, { name: event.target.value })} />
                </label>
                <label>
                  Condition
                  <select aria-label="Condition" value={str(alarm.mode) || "AboveValue"} onChange={(event) => patchAlarm(i, { mode: event.target.value })}>
                    {ALARM_MODES.map((m) => (
                      <option key={m} value={m}>
                        {alarmModeLabel(m)}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Priority
                  <select aria-label="Priority" value={str(alarm.priority) || "Medium"} onChange={(event) => patchAlarm(i, { priority: event.target.value })}>
                    {ALARM_PRIORITIES.map((p) => (
                      <option key={p}>{p}</option>
                    ))}
                  </select>
                </label>
                {alarm.mode !== "OutOfEngRange" && alarm.mode !== "Bit" ? (
                  <NumberField label={alarm.mode === "OutsideValues" ? "Low setpoint" : "Setpoint"} value={alarm.setpointA} onChange={(v) => patchAlarm(i, { setpointA: v })} />
                ) : null}
                {alarm.mode === "OutsideValues" ? <NumberField label="High setpoint" value={alarm.setpointB} onChange={(v) => patchAlarm(i, { setpointB: v })} /> : null}
                {alarm.mode === "Bit" ? <NumberField label="Bit" value={alarm.bitPosition} onChange={(v) => patchAlarm(i, { bitPosition: v })} /> : null}
                <NumberField label="Deadband" value={alarm.deadband} onChange={(v) => patchAlarm(i, { deadband: v })} />
                <NumberField label="On delay (s)" value={alarm.timeOnDelaySeconds} onChange={(v) => patchAlarm(i, { timeOnDelaySeconds: v })} />
                <button type="button" onClick={() => onPatch({ alarms: alarms.filter((_, j) => j !== i) })}>
                  Remove alarm {str(alarm.name) || i + 1}
                </button>
              </div>
            ))}
            <button type="button" onClick={() => onPatch({ alarms: [...alarms, { name: "", mode: "AboveValue", priority: "Medium" }] })}>
              Add alarm
            </button>
          </fieldset>

          <fieldset aria-label="History">
            <legend>History</legend>
            <label className="check-field">
              <input
                type="checkbox"
                name="member-history"
                checked={historyOn}
                onChange={(event) =>
                  onPatch({
                    history: event.target.checked
                      ? { sampleMode: "onChange", storageProvider: providers[0] ?? "local", ...history, enabled: true }
                      : patchSpec(history, { enabled: false }),
                  })
                }
              />
              Record history for this member
            </label>
            {historyOn ? (
              <div className="form-grid">
                <label>
                  History provider
                  <select aria-label="History provider" name="member-history-provider" value={str(history.storageProvider) || "local"} onChange={(event) => patchHistory({ storageProvider: event.target.value })}>
                    <option value="local">local (built-in)</option>
                    {providers.map((p) => (
                      <option key={p} value={p}>
                        {p}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Sample mode
                  <select aria-label="Sample mode" name="member-history-mode" value={str(history.sampleMode) || "onChange"} onChange={(event) => patchHistory({ sampleMode: event.target.value })}>
                    <option value="onChange">On change</option>
                    <option value="periodic">Periodic</option>
                  </select>
                </label>
                {str(history.sampleMode) === "periodic" ? (
                  <NumberField label="Sample rate (ms)" name="member-history-rate" value={history.sampleRateMillis} onChange={(v) => patchHistory({ sampleRateMillis: v })} />
                ) : null}
                <label>
                  History deadband
                  <select
                    aria-label="History deadband"
                    name="member-history-deadband-mode"
                    value={str(deadband.mode) || "off"}
                    onChange={(event) => patchHistory({ deadband: patchSpec(deadband, { mode: event.target.value }) })}
                  >
                    <option value="off">Off</option>
                    <option value="absolute">Absolute</option>
                    <option value="percent">Percent</option>
                  </select>
                </label>
                {str(deadband.mode) && deadband.mode !== "off" ? (
                  <NumberField
                    label="History deadband amount"
                    name="member-history-deadband"
                    value={deadband.value}
                    onChange={(v) => patchHistory({ deadband: patchSpec(deadband, { value: v }) })}
                  />
                ) : null}
                <NumberField
                  label="Max time between samples (ms)"
                  name="member-history-max"
                  value={history.maxTimeBetweenSamplesMillis}
                  onChange={(v) => patchHistory({ maxTimeBetweenSamplesMillis: v })}
                />
              </div>
            ) : null}
          </fieldset>
        </>
      ) : null}
    </section>
  );
}

function NumberField({ label, name, value, onChange }: { label: string; name?: string; value: unknown; onChange: (value: number | undefined) => void }) {
  const [text, setText] = useState(numText(value));
  useEffect(() => {
    if (toNumber(text) !== value) setText(numText(value));
  }, [value]);
  return (
    <label>
      {label}
      <input
        name={name}
        type="number"
        step="any"
        value={text}
        onChange={(event) => {
          setText(event.target.value);
          onChange(toNumber(event.target.value));
        }}
      />
    </label>
  );
}

function alarmModeLabel(mode: string): string {
  switch (mode) {
    case "AboveValue":
      return "Above setpoint";
    case "BelowValue":
      return "Below setpoint";
    case "Equality":
      return "Equal to setpoint";
    case "Bit":
      return "Bit is set";
    case "OutOfEngRange":
      return "Outside engineering range";
    case "OutsideValues":
      return "Outside low/high setpoints";
    default:
      return mode;
  }
}

function typedValue(text: string, dataType: string): unknown {
  if (text === "") return undefined;
  if (dataType === "Boolean") return text === "true" || text === "1";
  if (["Int32", "Int64", "Float", "Double"].includes(dataType)) {
    const n = Number(text);
    return Number.isFinite(n) ? n : text;
  }
  return text;
}
