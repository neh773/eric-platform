/**
 * Device connection drafts. The form writes the fields a person sets; settings the form does not
 * list ride along in `extra` so a JSON edit survives a later form save.
 * Doc source: FEATURES DEV-004 through DEV-038.
 */

export type DeviceKind =
  | "modbus"
  | "logix"
  | "s7"
  | "opcua"
  | "bacnet-local"
  | "bacnet-remote"
  | "dnp3"
  | "simulator"
  | "udp";

export interface DeviceTypeInfo {
  kind: DeviceKind;
  label: string;
  module: string;
  resourceKind: string;
  /** What the person is connecting to. */
  hint: string;
}

export const DEVICE_TYPES: DeviceTypeInfo[] = [
  { kind: "modbus", label: "Modbus", module: "org.oip.driver-modbus", resourceKind: "Device", hint: "A Modbus TCP or RTU-over-TCP device." },
  { kind: "logix", label: "EtherNet/IP (Logix)", module: "org.oip.driver-logix", resourceKind: "Device", hint: "A ControlLogix, CompactLogix, or Micro800 controller." },
  { kind: "s7", label: "Siemens S7", module: "org.oip.driver-s7", resourceKind: "Device", hint: "An S7-300, 400, 1200, or 1500 PLC." },
  { kind: "opcua", label: "OPC UA client", module: "org.oip.driver-opcua-client", resourceKind: "Connection", hint: "Another OPC UA server, including one from another vendor." },
  { kind: "bacnet-local", label: "BACnet local device", module: "org.oip.driver-bacnet", resourceKind: "LocalDevice", hint: "This gateway's own BACnet identity. Create this before a remote device." },
  { kind: "bacnet-remote", label: "BACnet remote device", module: "org.oip.driver-bacnet", resourceKind: "RemoteDevice", hint: "A BACnet/IP device reached through a local device." },
  { kind: "dnp3", label: "DNP3 outstation", module: "org.oip.driver-dnp3", resourceKind: "Outstation", hint: "A DNP3 outstation this gateway polls as master." },
  { kind: "simulator", label: "Simulator", module: "org.oip.driver-simulator", resourceKind: "Device", hint: "A programmed device for testing, with no hardware." },
  { kind: "udp", label: "UDP/TCP listener", module: "org.oip.driver-udp-tcp", resourceKind: "Device", hint: "A scanner, scale, or other device that sends delimited text." },
];

export interface DeviceDraft {
  kind: DeviceKind;
  enabled: boolean;
  host: string;
  port: string;
  mode: string;
  unitId: string;
  wordOrder: string;
  byteOrder: string;
  slot: string;
  connectionPath: string;
  profile: string;
  model: string;
  rack: string;
  endpointUrl: string;
  securityPolicy: string;
  securityMode: string;
  deviceInstance: string;
  deviceName: string;
  localDevice: string;
  ports: string;
  protocol: string;
  address: string;
  program: string;
  masterAddress: string;
  outstationAddress: string;
  extra: Record<string, unknown>;
}

const OWNED = new Set([
  "enabled", "host", "port", "mode", "unitId", "wordOrder", "byteOrder", "slot", "connectionPath", "profile",
  "model", "rack", "endpointUrl", "securityPolicy", "securityMode", "deviceInstance", "deviceName", "localDevice",
  "ports", "protocol", "address", "program", "masterAddress", "outstationAddress", "name",
]);

export function deviceType(kind: DeviceKind): DeviceTypeInfo {
  const found = DEVICE_TYPES.find((item) => item.kind === kind);
  if (!found) throw new Error(`unknown device kind ${kind}`);
  return found;
}

export function emptyDevice(kind: DeviceKind = "modbus"): DeviceDraft {
  return {
    kind,
    enabled: true,
    host: "",
    port: defaultPort(kind),
    mode: "TCP",
    unitId: "1",
    wordOrder: "HIGH_WORD_FIRST",
    byteOrder: "BIG_ENDIAN",
    slot: kind === "s7" ? "2" : "0",
    connectionPath: "",
    profile: "logix",
    model: "S7_300",
    rack: "0",
    endpointUrl: "opc.tcp://",
    securityPolicy: "NONE",
    securityMode: "NONE",
    deviceInstance: "1",
    deviceName: "",
    localDevice: "",
    ports: "9100",
    protocol: "tcp",
    address: "0.0.0.0",
    program: "generic",
    masterAddress: "1",
    outstationAddress: "1024",
    extra: {},
  };
}

export function draftFromSpec(kind: DeviceKind, spec: Record<string, unknown>): DeviceDraft {
  const base = emptyDevice(kind);
  const extra: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(spec)) if (!OWNED.has(key)) extra[key] = value;
  return {
    ...base,
    enabled: spec.enabled !== false,
    host: text(spec.host),
    port: spec.port === undefined ? base.port : String(spec.port),
    mode: text(spec.mode) || base.mode,
    unitId: spec.unitId === undefined ? base.unitId : String(spec.unitId),
    wordOrder: text(spec.wordOrder) || base.wordOrder,
    byteOrder: text(spec.byteOrder) || base.byteOrder,
    slot: spec.slot === undefined ? base.slot : String(spec.slot),
    connectionPath: text(spec.connectionPath),
    profile: text(spec.profile) || base.profile,
    model: text(spec.model) || base.model,
    rack: spec.rack === undefined ? base.rack : String(spec.rack),
    endpointUrl: text(spec.endpointUrl) || base.endpointUrl,
    securityPolicy: text(spec.securityPolicy) || base.securityPolicy,
    securityMode: text(spec.securityMode) || base.securityMode,
    deviceInstance: spec.deviceInstance === undefined ? base.deviceInstance : String(spec.deviceInstance),
    deviceName: text(spec.deviceName),
    localDevice: text(spec.localDevice),
    ports: Array.isArray(spec.ports) ? spec.ports.map(String).join(", ") : base.ports,
    protocol: text(spec.protocol) || base.protocol,
    address: text(spec.address) || base.address,
    program: text(spec.program) || base.program,
    masterAddress: spec.masterAddress === undefined ? base.masterAddress : String(spec.masterAddress),
    outstationAddress: spec.outstationAddress === undefined ? base.outstationAddress : String(spec.outstationAddress),
    extra,
  };
}

export function specFromDraft(draft: DeviceDraft): Record<string, unknown> {
  const spec: Record<string, unknown> = { ...draft.extra, enabled: draft.enabled };
  const port = numberOr(draft.port);
  switch (draft.kind) {
    case "modbus":
      Object.assign(spec, {
        host: draft.host.trim(),
        port: port ?? 502,
        mode: draft.mode,
        unitId: numberOr(draft.unitId) ?? 1,
        wordOrder: draft.wordOrder,
        byteOrder: draft.byteOrder,
      });
      break;
    case "logix":
      Object.assign(spec, {
        host: draft.host.trim(),
        port: port ?? 44818,
        slot: numberOr(draft.slot) ?? 0,
        connectionPath: draft.connectionPath.trim(),
        profile: draft.profile,
      });
      break;
    case "s7":
      Object.assign(spec, {
        host: draft.host.trim(),
        port: port ?? 102,
        model: draft.model,
        rack: numberOr(draft.rack) ?? 0,
        slot: numberOr(draft.slot) ?? 2,
      });
      break;
    case "opcua":
      Object.assign(spec, {
        endpointUrl: draft.endpointUrl.trim(),
        securityPolicy: draft.securityPolicy,
        securityMode: draft.securityMode,
      });
      break;
    case "bacnet-local":
      Object.assign(spec, {
        deviceInstance: numberOr(draft.deviceInstance) ?? 1,
        deviceName: draft.deviceName.trim() || "Gateway",
        port: port ?? 47808,
      });
      break;
    case "bacnet-remote":
      Object.assign(spec, {
        localDevice: draft.localDevice.trim(),
        deviceInstance: numberOr(draft.deviceInstance) ?? 1,
        host: draft.host.trim(),
        port: port ?? 47808,
      });
      break;
    case "dnp3":
      Object.assign(spec, {
        host: draft.host.trim(),
        port: port ?? 20000,
        masterAddress: numberOr(draft.masterAddress) ?? 1,
        outstationAddress: numberOr(draft.outstationAddress) ?? 1024,
      });
      break;
    case "simulator":
      spec.program = draft.program;
      break;
    case "udp":
      Object.assign(spec, {
        protocol: draft.protocol,
        address: draft.address.trim() || "0.0.0.0",
        ports: draft.ports.split(/[,\s]+/).map((item) => Number(item)).filter((item) => Number.isInteger(item) && item > 0),
      });
      break;
    default:
      break;
  }
  return spec;
}

export function deviceProblem(name: string, draft: DeviceDraft, creating: boolean): string | null {
  if (creating && !/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name)) {
    return "Use a short lowercase name: letters, digits, and hyphens.";
  }
  if (draft.kind === "opcua" && !/^opc\.tcp:\/\/.+/.test(draft.endpointUrl.trim())) {
    return "The endpoint must start with opc.tcp:// and name a host.";
  }
  if (draft.kind === "bacnet-local" && !draft.deviceName.trim()) return "A local BACnet device needs a device name.";
  if (draft.kind === "bacnet-remote" && !draft.localDevice.trim()) return "Name the local BACnet device this remote device uses.";
  if (draft.kind === "udp" && specFromDraft(draft).ports && (specFromDraft(draft).ports as number[]).length === 0) {
    return "A listener needs at least one port.";
  }
  if (needsHost(draft.kind) && !draft.host.trim()) return "A host name or address is required.";
  return null;
}

export function kindFor(moduleName: string, resourceKind: string): DeviceKind | null {
  return DEVICE_TYPES.find((item) => item.module === moduleName && item.resourceKind === resourceKind)?.kind ?? null;
}

function needsHost(kind: DeviceKind): boolean {
  return kind === "modbus" || kind === "logix" || kind === "s7" || kind === "dnp3" || kind === "bacnet-remote";
}

function defaultPort(kind: DeviceKind): string {
  switch (kind) {
    case "modbus": return "502";
    case "logix": return "44818";
    case "s7": return "102";
    case "dnp3": return "20000";
    case "bacnet-local":
    case "bacnet-remote": return "47808";
    default: return "";
  }
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function numberOr(value: string): number | undefined {
  if (value.trim() === "") return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}
