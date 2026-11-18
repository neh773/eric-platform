import { problemError, type ApiClient } from "./api";

/**
 * Enterprise administration calls (`/api/v1/eam`). Reads need gateway.read; changes need gateway.write, and backup
 * or restore tasks also backups.admin. Doc source: FEATURES EAM-001 to EAM-017.
 */

export const EAM_BASE = "/api/v1/eam";

export type EamRoleName = "none" | "controller" | "agent";

export interface EamSettings {
  role: EamRoleName;
  controller: string;
  backupController: string;
  group: string;
  checkInMillis: number;
  autoApprove: boolean;
  keepBackups: number;
}

export interface EamStatus {
  role: EamRoleName;
  gatewayName: string;
  settings: EamSettings;
  agent: {
    state: string;
    connection: string | null;
    controllerName: string | null;
    lastCheckInMillis: number | null;
    lastError: string | null;
  } | null;
  controller: {
    serving: boolean;
    agents: number;
    online: number;
    listenPort: number | null;
    fingerprint: string | null;
  } | null;
}

export interface EamAgent {
  name: string;
  approved: boolean;
  group: string;
  maintenance: boolean;
  status: string;
  online: boolean;
  lastCheckInMillis: number | null;
  version: string | null;
  queued: number;
  running: number;
  failed: number;
  thresholds: string[];
  backups: number;
}

export interface PendingCertificate {
  fingerprint: string;
  subject: string;
  gatewayName: string | null;
}

export interface EamTask {
  id: string;
  kind: string;
  agents: string[];
  groups: string[];
  schedule: string;
  enabled: boolean;
}

export interface EamRun {
  id: string;
  task: string;
  agent: string;
  kind: string;
  state: string;
  attempts: number;
  maxAttempts: number;
  createdMillis: number;
  message: string;
}

export interface EamEvent {
  timeMillis: number;
  agent: string;
  kind: string;
  detail: string;
}

export interface NewTask {
  id: string;
  kind: string;
  agents: string[];
  groups: string[];
  schedule: string;
  params?: Record<string, unknown>;
  payload?: string;
}

export const TASK_KINDS = ["backup", "restore", "module-install", "send-project", "send-tags", "upgrade"] as const;

async function read<T>(client: ApiClient, path: string): Promise<T> {
  const res = await client.request("GET", path);
  if (!res.ok) throw await problemError(res);
  return (await res.json()) as T;
}

async function write<T>(client: ApiClient, method: string, path: string, body: unknown): Promise<T> {
  const res = await client.request(method, path, body);
  if (!res.ok) throw await problemError(res);
  return (await res.json()) as T;
}

export const eamApi = {
  status: (client: ApiClient) => read<EamStatus>(client, EAM_BASE),
  saveSettings: (client: ApiClient, settings: Partial<EamSettings>) => write<EamStatus>(client, "PUT", `${EAM_BASE}/settings`, settings),
  agents: (client: ApiClient) =>
    read<{ agents: EamAgent[]; pendingCertificates: PendingCertificate[] }>(client, `${EAM_BASE}/agents`),
  approveCertificate: (client: ApiClient, fingerprint: string) =>
    write<unknown>(client, "POST", `/api/v1/gateway-network/incoming/${encodeURIComponent(fingerprint)}/approve`, {}),
  approveAgent: (client: ApiClient, name: string) =>
    write<EamAgent>(client, "POST", `${EAM_BASE}/agents/${encodeURIComponent(name)}/approve`, {}),
  recoverAgent: (client: ApiClient, name: string) =>
    write<unknown>(client, "POST", `${EAM_BASE}/agents/${encodeURIComponent(name)}/recover`, {}),
  updateAgent: (client: ApiClient, name: string, change: { group?: string; maintenance?: boolean }) =>
    write<EamAgent>(client, "PUT", `${EAM_BASE}/agents/${encodeURIComponent(name)}`, change),
  tasks: (client: ApiClient) => read<{ tasks: EamTask[] }>(client, `${EAM_BASE}/tasks`),
  createTask: (client: ApiClient, task: NewTask) => write<{ runs: EamRun[] }>(client, "POST", `${EAM_BASE}/tasks`, task),
  runTask: (client: ApiClient, id: string) => write<{ runs: EamRun[] }>(client, "POST", `${EAM_BASE}/tasks/${encodeURIComponent(id)}/run`, {}),
  retryRun: (client: ApiClient, id: string) => write<EamRun>(client, "POST", `${EAM_BASE}/runs/${encodeURIComponent(id)}/retry`, {}),
  runs: (client: ApiClient) => read<{ runs: EamRun[] }>(client, `${EAM_BASE}/runs?limit=50`),
  events: (client: ApiClient) => read<{ events: EamEvent[] }>(client, `${EAM_BASE}/events?limit=30`),
  checkIn: (client: ApiClient) => write<EamStatus>(client, "POST", `${EAM_BASE}/check-in`, {}),
};

export function splitNames(text: string): string[] {
  return text
    .split(/[,\s]+/)
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
}

export function formatMillis(millis: number | null | undefined): string {
  if (!millis) return "Never";
  return new Date(millis).toISOString().replace("T", " ").replace(/\.\d+Z$/, "Z");
}
