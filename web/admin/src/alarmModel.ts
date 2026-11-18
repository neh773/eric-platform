/**
 * Alarm notification drafts: rosters, pipelines, schedules, and contacts.
 * Extra JSON settings ride along so a JSON edit survives a form save.
 * Doc source: FEATURES ALA-001 through ALA-041.
 */

export type AlarmKind = "roster" | "pipeline" | "schedule" | "contact";

export const ALARM_MODULE = "alarm";

export const ALARM_KINDS: Record<AlarmKind, { label: string; resourceKind: string }> = {
  roster: { label: "Roster", resourceKind: "Roster" },
  pipeline: { label: "Pipeline", resourceKind: "Pipeline" },
  schedule: { label: "Schedule", resourceKind: "Schedule" },
  contact: { label: "Contact", resourceKind: "UserContact" },
};

export interface AlarmDraft {
  kind: AlarmKind;
  users: string;
  roster: string;
  profile: string;
  message: string;
  delaySeconds: string;
  dropOnAck: boolean;
  hours: string;
  email: string;
  phone: string;
  extra: Record<string, unknown>;
}

export function emptyAlarm(kind: AlarmKind = "roster"): AlarmDraft {
  return {
    kind,
    users: "",
    roster: "",
    profile: "",
    message: "Alarm {name} is {eventState}",
    delaySeconds: "",
    dropOnAck: true,
    hours: "08:00-17:00",
    email: "",
    phone: "",
    extra: {},
  };
}

export function specFromAlarm(draft: AlarmDraft): Record<string, unknown> {
  const spec: Record<string, unknown> = { ...draft.extra };
  if (draft.kind === "roster") {
    spec.users = splitNames(draft.users);
  }
  if (draft.kind === "pipeline") {
    const blocks: Record<string, unknown>[] = [{ id: "start", type: "start", next: "notify" }];
    const notify: Record<string, unknown> = {
      id: "notify",
      type: "notification",
      roster: draft.roster.trim(),
      next: draft.delaySeconds.trim() ? "wait" : "end",
    };
    if (draft.profile.trim()) notify.profile = draft.profile.trim();
    if (draft.message.trim()) notify.message = draft.message;
    blocks.push(notify);
    if (draft.delaySeconds.trim()) {
      blocks.push({ id: "wait", type: "delay", seconds: Number(draft.delaySeconds), next: "end" });
    }
    blocks.push({ id: "end", type: "end" });
    spec.blocks = blocks;
    spec.dropOnAck = draft.dropOnAck;
  }
  if (draft.kind === "schedule") {
    spec.type = "standard";
    spec.weekdays = draft.hours.trim();
  }
  if (draft.kind === "contact") {
    const contacts = [];
    if (draft.email.trim()) contacts.push({ type: "email", value: draft.email.trim() });
    if (draft.phone.trim()) contacts.push({ type: "phone", value: draft.phone.trim() });
    spec.contacts = contacts;
  }
  return spec;
}

export function alarmFromSpec(kind: AlarmKind, spec: Record<string, unknown>): AlarmDraft {
  const base = emptyAlarm(kind);
  const blocks = Array.isArray(spec.blocks) ? spec.blocks.filter(isRecord) : [];
  const notify = blocks.find((block) => block.type === "notification");
  const delay = blocks.find((block) => block.type === "delay");
  const contacts = Array.isArray(spec.contacts) ? spec.contacts.filter(isRecord) : [];
  const extra: Record<string, unknown> = {};
  const owned = new Set(["users", "blocks", "dropOnAck", "type", "weekdays", "contacts"]);
  for (const [key, value] of Object.entries(spec)) if (!owned.has(key)) extra[key] = value;
  return {
    ...base,
    users: Array.isArray(spec.users) ? spec.users.map(String).join(", ") : "",
    roster: typeof notify?.roster === "string" ? notify.roster : "",
    profile: typeof notify?.profile === "string" ? notify.profile : "",
    message: typeof notify?.message === "string" ? notify.message : base.message,
    delaySeconds: typeof delay?.seconds === "number" ? String(delay.seconds) : "",
    dropOnAck: spec.dropOnAck !== false,
    hours: typeof spec.weekdays === "string" ? spec.weekdays : base.hours,
    email: textContact(contacts, "email"),
    phone: textContact(contacts, "phone"),
    extra,
  };
}

export function alarmProblem(name: string, draft: AlarmDraft, creating: boolean): string | null {
  if (creating && !/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name)) {
    return "Use a short lowercase name: letters, digits, and hyphens.";
  }
  if (draft.kind === "roster" && splitNames(draft.users).some((user) => !/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(user))) {
    return "Each roster user is a lowercase name.";
  }
  if (draft.kind === "pipeline" && !/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(draft.roster.trim())) {
    return "A pipeline needs the roster it notifies.";
  }
  if (draft.kind === "pipeline" && draft.delaySeconds.trim() && !Number.isFinite(Number(draft.delaySeconds))) {
    return "Delay must be a number of seconds.";
  }
  if (draft.kind === "contact" && !draft.email.trim() && !draft.phone.trim()) {
    return "A contact needs an email address or a phone number.";
  }
  return null;
}

function splitNames(value: string): string[] {
  return value.split(",").map((item) => item.trim()).filter(Boolean);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function textContact(contacts: Record<string, unknown>[], type: string): string {
  const found = contacts.find((item) => item.type === type);
  return typeof found?.value === "string" ? found.value : "";
}
