import { ApiError, type ApiClient } from "./api";

/**
 * Store-and-forward status, quarantine, and alarm-journal counters for the admin page.
 *
 * The figures come from routes that already exist, plus the quarantine export download:
 * `/api/v1/diagnostics/history/providers`, `/api/v1/diagnostics/history/quarantine`,
 * `/api/v1/diagnostics/history/quarantine/export`, and `/api/v1/diagnostics/alarms/journal`.
 * Viewing needs gateway.read. The quarantine list also needs tags.read. Retry, delete, export,
 * import, and disk-cache archive and load need gateway.write.
 *
 * Buffer fill is the number of records in that provider's durable cache. The cache has no record
 * cap, so the figure is a count. Store rate is the provider's `storeRate`: datapoints per second
 * written into that disk cache over the last 10 seconds. Forward throughput is the change in the
 * provider's cumulative forwarded counter between polls.
 *
 */

/** Fastest refresh that still leaves a full second between polls. */
export const STORE_FORWARD_POLL_MS = 1_000;

export const PROVIDERS_PATH = "/api/v1/diagnostics/history/providers";
export const QUARANTINE_PATH = "/api/v1/diagnostics/history/quarantine";
export const QUARANTINE_EXPORT_PATH = "/api/v1/diagnostics/history/quarantine/export";
export const QUARANTINE_IMPORT_PATH = "/api/v1/diagnostics/history/quarantine/import";
export const DISK_CACHE_ARCHIVE_PATH = "/api/v1/diagnostics/history/cache/archive";
export const DISK_CACHE_ARCHIVES_PATH = "/api/v1/diagnostics/history/cache/archives";
export const DISK_CACHE_LOAD_PATH = "/api/v1/diagnostics/history/cache/load";
export const JOURNAL_PATH = "/api/v1/diagnostics/alarms/journal";
export const QUARANTINE_LIMIT = 1000;

export interface HistoryProviderStatus {
  name: string;
  pending: number;
  quarantined: number;
  forwarded: number;
  /** Datapoints per second written into this provider's disk cache over the last 10 seconds. */
  storeRate: number;
  connected: boolean | null;
  storeAndForward: boolean;
  configured: boolean;
  lastError: string | null;
}

export interface QuarantineRecord {
  id: number;
  provider: string;
  tagPath: string;
  timestamp: string;
  value: unknown;
  quality: string;
  error: string;
  quarantinedAt: string;
}

export interface JournalCounters {
  queued: number;
  retrying: boolean;
  lostRows: number;
}

export interface CollectorCounters {
  pending: number;
  dropped: number;
}

export interface DiskCacheArchiveItem {
  name: string;
  provider: string;
  records: number;
  bytes: number;
  created: string;
}

export interface StoreForwardLoaded {
  providers: HistoryProviderStatus[];
  collector: CollectorCounters;
  records: QuarantineRecord[];
  journal: JournalCounters;
  quarantineDenied: boolean;
  forwarded: Record<string, number>;
  archives: DiskCacheArchiveItem[];
}

export interface RateSample {
  at: number;
  forwarded: Record<string, number>;
}

function num(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" ? (value as Record<string, unknown>) : {};
}

/** Records per second from the cumulative forwarded counter. Null until two samples exist, or after a restart. */
export function forwardRates(previous: RateSample | null, next: RateSample): Record<string, number | null> {
  const rates: Record<string, number | null> = {};
  for (const name of Object.keys(next.forwarded)) {
    const before = previous?.forwarded[name];
    if (previous == null || before == null || next.at <= previous.at) {
      rates[name] = null;
      continue;
    }
    const delta = next.forwarded[name] - before;
    rates[name] = delta < 0 ? null : delta / ((next.at - previous.at) / 1000);
  }
  return rates;
}

export function formatRate(rate: number | null): string {
  if (rate == null || !Number.isFinite(rate)) return "—";
  return `${rate.toFixed(1)} /s`;
}

/** Durable-cache occupancy. The cache has no record cap, so this is a count of records. */
export function formatBufferFill(pending: number): string {
  return `${pending} records`;
}

/**
 * Download link for the rows on screen. An empty id list asks for id 0, which matches nothing,
 * so an empty table downloads an empty file instead of every quarantined record.
 */
export function quarantineExportPath(opts: {
  format: "json" | "csv";
  provider?: string;
  ids?: number[];
  limit?: number;
}): string {
  const params = new URLSearchParams();
  params.set("format", opts.format);
  params.set("limit", String(opts.limit ?? QUARANTINE_LIMIT));
  if (opts.provider) params.set("provider", opts.provider);
  if (opts.ids) params.set("ids", opts.ids.length ? opts.ids.join(",") : "0");
  return `${QUARANTINE_EXPORT_PATH}?${params.toString()}`;
}

/** Stable comparison key. The poll skips a re-render when this does not change. */
export function snapshotKey(loaded: StoreForwardLoaded, rates: Record<string, number | null>): string {
  return JSON.stringify({ loaded, rates });
}

export function parseStoreForward(
  providersBody: unknown,
  quarantineBody: unknown,
  journalBody: unknown,
  quarantineDenied: boolean,
): StoreForwardLoaded {
  const providersRoot = record(providersBody);
  const collectorRaw = record(providersRoot.collector);
  const providers = (Array.isArray(providersRoot.providers) ? providersRoot.providers : []).map((item) => {
    const row = record(item);
    const connected = row.connected === true ? true : row.connected === false ? false : null;
    return {
      name: typeof row.name === "string" ? row.name : "",
      pending: num(row.pending),
      quarantined: num(row.quarantined),
      forwarded: num(row.forwarded),
      storeRate: num(row.storeRate),
      connected,
      storeAndForward: row.storeAndForward === true,
      configured: row.configured === true,
      lastError: typeof row.lastError === "string" ? row.lastError : null,
    };
  });
  const listed = record(quarantineBody).records;
  const rawRecords: unknown[] = Array.isArray(listed) ? listed : [];
  const records = rawRecords.flatMap((item) => {
    const row = record(item);
    if (typeof row.id !== "number") return [];
    return [
      {
        id: row.id,
        provider: typeof row.provider === "string" ? row.provider : "",
        tagPath: typeof row.tagPath === "string" ? row.tagPath : "",
        timestamp: typeof row.timestamp === "string" ? row.timestamp : "",
        value: row.value ?? null,
        quality: typeof row.quality === "string" ? row.quality : "",
        error: typeof row.error === "string" ? row.error : "",
        quarantinedAt: typeof row.quarantinedAt === "string" ? row.quarantinedAt : "",
      },
    ];
  });
  const journalRaw = record(journalBody);
  const forwarded: Record<string, number> = {};
  for (const provider of providers) forwarded[provider.name] = provider.forwarded;
  return {
    providers,
    collector: { pending: num(collectorRaw.pending), dropped: num(collectorRaw.dropped) },
    records: quarantineDenied ? [] : records,
    journal: {
      queued: num(journalRaw.queued),
      retrying: journalRaw.retrying === true,
      lostRows: num(journalRaw.lostRows),
    },
    quarantineDenied,
    forwarded,
    archives: [],
  };
}

export function parseArchives(body: unknown): DiskCacheArchiveItem[] {
  const listed = record(body).archives;
  if (!Array.isArray(listed)) return [];
  return listed.flatMap((item) => {
    const row = record(item);
    if (typeof row.name !== "string" || row.name.length === 0) return [];
    return [
      {
        name: row.name,
        provider: typeof row.provider === "string" ? row.provider : "",
        records: num(row.records),
        bytes: num(row.bytes),
        created: typeof row.created === "string" ? row.created : "",
      },
    ];
  });
}

async function expectOk(res: Response): Promise<unknown> {
  if (!res.ok) {
    let detail = res.statusText || "Request failed";
    try {
      const body = record(await res.json());
      if (typeof body.detail === "string" && body.detail) detail = body.detail;
    } catch {
      // The failure body was not JSON.
    }
    throw new ApiError(detail, res.status);
  }
  return res.json();
}

/** One refresh: provider status, the quarantine list, and the alarm journal counters. */
export async function loadStoreForward(client: ApiClient): Promise<StoreForwardLoaded> {
  const [providersRes, quarantineRes, journalRes, archivesRes] = await Promise.all([
    client.request("GET", PROVIDERS_PATH),
    client.request("GET", `${QUARANTINE_PATH}?limit=${QUARANTINE_LIMIT}`),
    client.request("GET", JOURNAL_PATH),
    client.request("GET", DISK_CACHE_ARCHIVES_PATH),
  ]);
  const providersBody = await expectOk(providersRes);
  const journalBody = await expectOk(journalRes);
  const archives = archivesRes.ok ? parseArchives(await archivesRes.json()) : [];
  const loaded = quarantineRes.status === 403
    ? parseStoreForward(providersBody, { records: [] }, journalBody, true)
    : parseStoreForward(providersBody, await expectOk(quarantineRes), journalBody, false);
  return { ...loaded, archives };
}
