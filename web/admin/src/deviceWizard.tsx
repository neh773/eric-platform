import { useCallback, useEffect, useState, type FormEvent } from "react";
import { errorMessage } from "./access";
import type { ApiClient, ResourceDocument } from "./api";
import {
  DEVICE_TYPES,
  deviceProblem,
  deviceType,
  draftFromSpec,
  emptyDevice,
  kindFor,
  specFromDraft,
  type DeviceDraft,
  type DeviceKind,
} from "./deviceModel";

/**
 * Create, edit and delete a device connection without writing JSON.
 * Edit as JSON keeps settings the form does not list.
 * Doc source: FEATURES DEV-004..DEV-038.
 */

interface StoredDevice {
  kind: DeviceKind;
  resource: ResourceDocument;
}

interface Editing {
  name: string;
  version: number | null;
  draft: DeviceDraft;
  jsonText: string;
  mode: "form" | "json";
}

export function DeviceWizard({ client, canWrite }: { client: ApiClient; canWrite: boolean }) {
  const [devices, setDevices] = useState<StoredDevice[]>([]);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    const lists = await Promise.all(DEVICE_TYPES.map(async (type) => {
      try {
        const items = await client.listResources(type.module, "gateway", type.resourceKind);
        return items.map((resource) => ({ kind: type.kind, resource }));
      } catch {
        return [] as StoredDevice[];
      }
    }));
    setDevices(lists.flat());
  }, [client]);

  useEffect(() => {
    let gone = false;
    void reload().catch((err: unknown) => {
      if (!gone) setFailure(errorMessage(err, "Devices could not be loaded."));
    });
    return () => {
      gone = true;
    };
  }, [reload]);

  async function run(work: () => Promise<void>) {
    setBusy(true);
    setFailure(null);
    setNotice(null);
    try {
      await work();
    } catch (err) {
      setFailure(errorMessage(err, err instanceof Error ? err.message : "The device could not be saved."));
    } finally {
      setBusy(false);
    }
  }

  function openNew() {
    const draft = emptyDevice();
    setEditing({ name: "", version: null, draft, jsonText: pretty(specFromDraft(draft)), mode: "form" });
  }

  function openStored(item: StoredDevice) {
    const draft = draftFromSpec(item.kind, item.resource.spec);
    setEditing({
      name: item.resource.metadata.name,
      version: item.resource.metadata.version,
      draft,
      jsonText: pretty(item.resource.spec),
      mode: "form",
    });
  }

  function save(event: FormEvent) {
    event.preventDefault();
    if (!editing) return;
    void run(async () => {
      const spec = editing.mode === "json" ? parseObject(editing.jsonText) : specFromDraft(editing.draft);
      const draft = editing.mode === "json" ? draftFromSpec(editing.draft.kind, spec) : editing.draft;
      const problem = deviceProblem(editing.name.trim(), draft, editing.version == null);
      if (problem) throw new Error(problem);
      const type = deviceType(draft.kind);
      if (draft.kind === "mqtt" && editing.mode === "form") {
        const name = editing.name.trim();
        if (draft.username.trim()) {
          spec.username = (await client.saveSecret(`mqtt-${name}-user`, draft.username.trim(), `Username for MQTT device ${name}`)).reference;
        }
        if (draft.newPassword) {
          spec.password = (await client.saveSecret(`mqtt-${name}-password`, draft.newPassword, `Password for MQTT device ${name}`)).reference;
        }
      }
      if (draft.kind === "opcua" && draft.newPassword && editing.mode === "form") {
        const secret = await client.saveSecret(`opcua-${editing.name.trim()}`, draft.newPassword, `Password for OPC UA device ${editing.name.trim()}`);
        spec.password = secret.reference;
      }
      const saved = editing.version == null
        ? await client.createResource(type.module, "gateway", type.resourceKind, editing.name.trim(), spec)
        : await client.putResource(type.module, "gateway", type.resourceKind, editing.name, {
            apiVersion: "oip/v1",
            kind: type.resourceKind,
            metadata: { name: editing.name, module: type.module, version: editing.version },
            spec,
          });
      const kind = kindFor(saved.metadata.module, saved.kind) ?? draft.kind;
      openStored({ kind, resource: saved });
      setNotice(`Saved ${saved.metadata.name}.`);
      await reload();
    });
  }

  function remove() {
    if (!editing || editing.version == null) return;
    const type = deviceType(editing.draft.kind);
    const { name, version } = editing;
    void run(async () => {
      await client.deleteResource(type.module, "gateway", type.resourceKind, name, version);
      setEditing(null);
      setNotice(`Deleted ${name}.`);
      await reload();
    });
  }

  return (
    <section aria-label="Device wizard">
      <h2>Devices</h2>
      <p>
        Add a device connection here. The list above shows whether it is connected. Modbus addresses look like
        HR1 (holding register), IR1 (input register), C1 (coil) and DI1 (discrete input). A tag dropped on a
        screen uses the device browse path the driver publishes.
      </p>
      {failure ? <p role="alert">{failure}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {devices.length === 0 ? <p>No device connections yet.</p> : (
        <table className="data-table" aria-label="Device connections">
          <thead>
            <tr><th>Name</th><th>Type</th><th>Address</th><th></th></tr>
          </thead>
          <tbody>
            {devices.map((item) => (
              <tr key={`${item.kind}:${item.resource.metadata.name}`}>
                <td>{item.resource.metadata.name}</td>
                <td>{deviceType(item.kind).label}</td>
                <td>{addressOf(item)}</td>
                <td>
                  {canWrite ? (
                    <button type="button" className="quiet" onClick={() => openStored(item)}>Edit {item.resource.metadata.name}</button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canWrite && !editing ? <p><button type="button" onClick={openNew}>New device</button></p> : null}
      {editing ? (
        <form className="stack-form" aria-label={editing.version == null ? "New device" : `Device ${editing.name}`} onSubmit={save}>
          <h3>{editing.version == null ? "New device" : editing.name}</h3>
          <p>{deviceType(editing.draft.kind).hint}</p>
          <div className="row-actions">
            <button
              type="button"
              className="quiet"
              onClick={() => {
                if (editing.mode === "json") {
                  try {
                    const spec = parseObject(editing.jsonText);
                    setEditing({ ...editing, mode: "form", draft: draftFromSpec(editing.draft.kind, spec) });
                    setFailure(null);
                  } catch (err) {
                    setFailure(err instanceof Error ? err.message : "The JSON could not be read.");
                  }
                  return;
                }
                setEditing({ ...editing, mode: "json", jsonText: pretty(specFromDraft(editing.draft)) });
              }}
            >
              {editing.mode === "json" ? "Edit as form" : "Edit as JSON"}
            </button>
            <button type="button" className="quiet" onClick={() => setEditing(null)}>Close</button>
          </div>
          {editing.mode === "json" ? (
            <label>
              Device JSON
              <textarea className="json-editor" aria-label="Device JSON" value={editing.jsonText} onChange={(event) => setEditing({ ...editing, jsonText: event.target.value })} />
            </label>
          ) : (
            <DeviceFields
              name={editing.name}
              creating={editing.version == null}
              draft={editing.draft}
              localDevices={devices.filter((item) => item.kind === "bacnet-local").map((item) => item.resource.metadata.name)}
              onName={(name) => setEditing({ ...editing, name })}
              onChange={(draft) => setEditing({ ...editing, draft })}
            />
          )}
          <div className="row-actions">
            <button type="submit" disabled={busy}>Save device</button>
            {editing.version != null ? (
              <button type="button" className="quiet" disabled={busy} onClick={remove}>Delete {editing.name}</button>
            ) : null}
          </div>
        </form>
      ) : null}
    </section>
  );
}

function DeviceFields({
  name,
  creating,
  draft,
  localDevices,
  onName,
  onChange,
}: {
  name: string;
  creating: boolean;
  draft: DeviceDraft;
  localDevices: string[];
  onName: (name: string) => void;
  onChange: (draft: DeviceDraft) => void;
}) {
  const set = <K extends keyof DeviceDraft>(key: K, value: DeviceDraft[K]) => onChange({ ...draft, [key]: value });
  return (
    <div className="stage-grid">
      {creating ? (
        <>
          <label>
            Device name
            <input aria-label="Device name" value={name} onChange={(event) => onName(event.target.value)} autoComplete="off" />
          </label>
          <label>
            Device type
            <select
              aria-label="Device type"
              value={draft.kind}
              onChange={(event) => onChange({ ...emptyDevice(event.target.value as DeviceKind), extra: {} })}
            >
              {DEVICE_TYPES.map((item) => <option key={item.kind} value={item.kind}>{item.label}</option>)}
            </select>
          </label>
        </>
      ) : null}
      <label>
        Enabled
        <input type="checkbox" checked={draft.enabled} onChange={(event) => set("enabled", event.target.checked)} />
      </label>
      {draft.kind === "opcua" ? (
        <>
          <label>Endpoint URL<input aria-label="Endpoint URL" value={draft.endpointUrl} onChange={(event) => set("endpointUrl", event.target.value)} placeholder="opc.tcp://server:4840" /></label>
          <label>
            Security policy
            <select aria-label="Security policy" value={draft.securityPolicy} onChange={(event) => set("securityPolicy", event.target.value)}>
              {["NONE", "BASIC256SHA256", "AES128_SHA256_RSAOAEP", "AES256_SHA256_RSAPSS"].map((item) => <option key={item}>{item}</option>)}
            </select>
          </label>
          <label>
            Security mode
            <select aria-label="Security mode" value={draft.securityMode} onChange={(event) => set("securityMode", event.target.value)}>
              {["NONE", "SIGN", "SIGN_AND_ENCRYPT"].map((item) => <option key={item}>{item}</option>)}
            </select>
          </label>
          <label>
            Trust the server certificate
            <input type="checkbox" aria-label="Trust the server certificate" checked={draft.trustServerCertificate} onChange={(event) => set("trustServerCertificate", event.target.checked)} />
          </label>
          <label>Username<input aria-label="Username" value={draft.username} onChange={(event) => set("username", event.target.value)} placeholder="Blank signs in anonymously" autoComplete="off" /></label>
          <label>
            Password
            <input aria-label="Password" type="password" value={draft.newPassword} onChange={(event) => set("newPassword", event.target.value)} placeholder={draft.passwordRef ? "Saved. Type to replace it." : ""} autoComplete="new-password" />
          </label>
          <p className="hint">The password is kept in the gateway's encrypted secret store. A secure policy needs the server certificate trusted.</p>
        </>
      ) : null}
      {draft.kind === "simulator" ? (
        <label>
          Program
          <select aria-label="Program" value={draft.program} onChange={(event) => set("program", event.target.value)}>
            {["none", "generic", "dairy", "slc"].map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </label>
      ) : null}
      {draft.kind === "udp" ? (
        <>
          <label>
            Protocol
            <select aria-label="Protocol" value={draft.protocol} onChange={(event) => set("protocol", event.target.value)}>
              <option value="tcp">TCP</option>
              <option value="udp">UDP</option>
            </select>
          </label>
          <label>Listen address<input aria-label="Listen address" value={draft.address} onChange={(event) => set("address", event.target.value)} /></label>
          <label>Ports<input aria-label="Ports" value={draft.ports} onChange={(event) => set("ports", event.target.value)} placeholder="9100" /></label>
        </>
      ) : null}
      {draft.kind === "mqtt" ? (
        <>
          <label>
            MQTT version
            <select aria-label="MQTT version" value={draft.mqttProtocol} onChange={(event) => set("mqttProtocol", event.target.value)}>
              <option value="MQTT_3_1_1">3.1.1</option>
              <option value="MQTT_5">5</option>
            </select>
          </label>
          <label>Client ID<input aria-label="Client ID" value={draft.clientId} onChange={(event) => set("clientId", event.target.value)} placeholder="Blank picks one" /></label>
          <label>
            Use TLS
            <input type="checkbox" aria-label="Use TLS" checked={draft.tls} onChange={(event) => set("tls", event.target.checked)} />
          </label>
          <label>Sparkplug host ID<input aria-label="Sparkplug host ID" value={draft.sparkplugHostId} onChange={(event) => set("sparkplugHostId", event.target.value)} placeholder="Blank: not a Sparkplug primary host" /></label>
          <label>Username<input aria-label="Username" value={draft.username} onChange={(event) => set("username", event.target.value)} placeholder={draft.usernameRef ? "Saved. Type to replace it." : "Blank: no sign-in"} autoComplete="off" /></label>
          <label>
            Password
            <input aria-label="Password" type="password" value={draft.newPassword} onChange={(event) => set("newPassword", event.target.value)} placeholder={draft.passwordRef ? "Saved. Type to replace it." : ""} autoComplete="new-password" />
          </label>
          <p className="hint">The username and password are kept in the gateway's encrypted secret store.</p>
        </>
      ) : null}
      {draft.kind === "bacnet-local" ? (
        <>
          <label>Device name<input aria-label="BACnet device name" value={draft.deviceName} onChange={(event) => set("deviceName", event.target.value)} /></label>
          <label>Device instance<input aria-label="Device instance" inputMode="numeric" value={draft.deviceInstance} onChange={(event) => set("deviceInstance", event.target.value)} /></label>
        </>
      ) : null}
      {draft.kind === "bacnet-remote" ? (
        <>
          <label>
            Local device
            <input aria-label="Local device" list="bacnet-local-devices" value={draft.localDevice} onChange={(event) => set("localDevice", event.target.value)} autoComplete="off" />
            <datalist id="bacnet-local-devices">{localDevices.map((local) => <option key={local} value={local} />)}</datalist>
          </label>
          {localDevices.length === 0 ? (
            <p className="hint">A remote device is reached through a BACnet local device on this gateway. Create a "BACnet local device" first.</p>
          ) : draft.localDevice.trim() && !localDevices.includes(draft.localDevice.trim()) ? (
            <p className="hint">No BACnet local device is named {draft.localDevice.trim()}. Local devices: {localDevices.join(", ")}.</p>
          ) : null}
          <label>Device instance<input aria-label="Device instance" inputMode="numeric" value={draft.deviceInstance} onChange={(event) => set("deviceInstance", event.target.value)} /></label>
        </>
      ) : null}
      {draft.kind === "modbus" || draft.kind === "logix" || draft.kind === "s7" || draft.kind === "dnp3" || draft.kind === "bacnet-remote" || draft.kind === "mqtt" ? (
        <label>Host<input aria-label="Host" value={draft.host} onChange={(event) => set("host", event.target.value)} placeholder="10.1.1.20" /></label>
      ) : null}
      {draft.kind !== "simulator" && draft.kind !== "opcua" && draft.kind !== "udp" ? (
        <label>Port<input aria-label="Port" inputMode="numeric" value={draft.port} onChange={(event) => set("port", event.target.value)} /></label>
      ) : null}
      {draft.kind === "modbus" ? (
        <>
          <label>
            Mode
            <select aria-label="Mode" value={draft.mode} onChange={(event) => set("mode", event.target.value)}>
              <option value="TCP">TCP</option>
              <option value="RTU_OVER_TCP">RTU over TCP</option>
            </select>
          </label>
          <label>Unit ID<input aria-label="Unit ID" inputMode="numeric" value={draft.unitId} onChange={(event) => set("unitId", event.target.value)} /></label>
          <label>
            Word order
            <select aria-label="Word order" value={draft.wordOrder} onChange={(event) => set("wordOrder", event.target.value)}>
              <option value="HIGH_WORD_FIRST">High word first</option>
              <option value="LOW_WORD_FIRST">Low word first</option>
            </select>
          </label>
          <label>
            Byte order
            <select aria-label="Byte order" value={draft.byteOrder} onChange={(event) => set("byteOrder", event.target.value)}>
              <option value="BIG_ENDIAN">Big endian</option>
              <option value="LITTLE_ENDIAN">Little endian</option>
            </select>
          </label>
        </>
      ) : null}
      {draft.kind === "logix" ? (
        <>
          <label>
            Profile
            <select aria-label="Profile" value={draft.profile} onChange={(event) => set("profile", event.target.value)}>
              <option value="logix">ControlLogix / CompactLogix</option>
              <option value="legacy-controllogix">Legacy ControlLogix</option>
              <option value="legacy-compactlogix">Legacy CompactLogix</option>
              <option value="micro800">Micro800</option>
            </select>
          </label>
          <label>Slot<input aria-label="Slot" inputMode="numeric" value={draft.slot} onChange={(event) => set("slot", event.target.value)} /></label>
          <label>Connection path<input aria-label="Connection path" value={draft.connectionPath} onChange={(event) => set("connectionPath", event.target.value)} placeholder="1,0" /></label>
        </>
      ) : null}
      {draft.kind === "s7" ? (
        <>
          <label>
            Model
            <select aria-label="Model" value={draft.model} onChange={(event) => set("model", event.target.value)}>
              {["S7_300", "S7_400", "S7_1200", "S7_1500"].map((item) => <option key={item} value={item}>{item.replace("_", "-")}</option>)}
            </select>
          </label>
          <label>Rack<input aria-label="Rack" inputMode="numeric" value={draft.rack} onChange={(event) => set("rack", event.target.value)} /></label>
          <label>Slot<input aria-label="Slot" inputMode="numeric" value={draft.slot} onChange={(event) => set("slot", event.target.value)} /></label>
        </>
      ) : null}
      {draft.kind === "dnp3" ? (
        <>
          <label>Master address<input aria-label="Master address" inputMode="numeric" value={draft.masterAddress} onChange={(event) => set("masterAddress", event.target.value)} /></label>
          <label>Outstation address<input aria-label="Outstation address" inputMode="numeric" value={draft.outstationAddress} onChange={(event) => set("outstationAddress", event.target.value)} /></label>
        </>
      ) : null}
    </div>
  );
}

function addressOf(item: StoredDevice): string {
  const spec = item.resource.spec;
  if (typeof spec.endpointUrl === "string") return spec.endpointUrl;
  if (typeof spec.host === "string" && spec.host) return spec.port ? `${spec.host}:${spec.port}` : spec.host;
  if (typeof spec.program === "string") return spec.program;
  if (Array.isArray(spec.ports)) return spec.ports.join(", ");
  return "";
}

function parseObject(text: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(text) as unknown;
  } catch {
    throw new Error("The JSON could not be read.");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("The device JSON must be an object.");
  return value as Record<string, unknown>;
}

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2);
}
