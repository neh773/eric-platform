import { memo, useEffect, useRef, useState } from "react";
import { allows, errorMessage } from "./access";
import { ApiError, type ApiClient } from "./api";
import type { AdminPageProps } from "./pages";
import {
  DISK_CACHE_ARCHIVES_PATH,
  DISK_CACHE_ARCHIVE_PATH,
  DISK_CACHE_LOAD_PATH,
  QUARANTINE_IMPORT_PATH,
  STORE_FORWARD_POLL_MS,
  formatBufferFill,
  formatRate,
  forwardRates,
  loadStoreForward,
  quarantineExportPath,
  snapshotKey,
  type HistoryProviderStatus,
  type QuarantineRecord,
  type StoreForwardLoaded,
} from "./storeForward";

/**
 * Store-and-forward status, the quarantine list, and alarm-journal loss counters.
 *
 * The page polls once a second and keeps the last snapshot on screen. A poll that returns the same
 * figures does not render again, so the tables are not torn down between refreshes.
 *
 */

function useStoreForward(client: ApiClient | undefined, enabled: boolean, reloadToken: number) {
  const [data, setData] = useState<StoreForwardLoaded | null>(null);
  const [rates, setRates] = useState<Record<string, number | null>>({});
  const [error, setError] = useState<string | null>(null);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const lastKey = useRef("");
  const previous = useRef<{ at: number; forwarded: Record<string, number> } | null>(null);

  useEffect(() => {
    if (!enabled || !client) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    lastKey.current = "";
    const tick = () => {
      loadStoreForward(client)
        .then((loaded) => {
          if (cancelled) return;
          const at = Date.now();
          const nextRates = forwardRates(previous.current, { at, forwarded: loaded.forwarded });
          previous.current = { at, forwarded: loaded.forwarded };
          const key = snapshotKey(loaded, nextRates);
          if (key !== lastKey.current) {
            lastKey.current = key;
            setData(loaded);
            setRates(nextRates);
            setUpdatedAt(at);
          }
          setError(null);
        })
        .catch((err: unknown) => {
          if (cancelled) return;
          setError(errorMessage(err, "Could not read store-and-forward status"));
        })
        .finally(() => {
          if (!cancelled) timer = setTimeout(tick, STORE_FORWARD_POLL_MS);
        });
    };
    tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [client, enabled, reloadToken]);

  return { data, rates, error, updatedAt };
}

function connectionLabel(connected: boolean | null): string {
  if (connected === true) return "Connected";
  if (connected === false) return "Faulted";
  return "Unknown";
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

const ProviderRow = memo(function ProviderRow({
  provider,
  rate,
  showDiskCache,
  onArchive,
}: {
  provider: HistoryProviderStatus;
  rate: number | null;
  showDiskCache: boolean;
  onArchive: (name: string) => void;
}) {
  return (
    <tr data-testid={`saf-${provider.name}`}>
      <td>{provider.name}</td>
      <td>{connectionLabel(provider.connected)}</td>
      <td data-testid={`saf-pending-${provider.name}`}>{provider.pending}</td>
      <td data-testid={`saf-buffer-${provider.name}`}>{formatBufferFill(provider.pending)}</td>
      <td data-testid={`saf-store-rate-${provider.name}`}>{formatRate(provider.storeRate)}</td>
      <td data-testid={`saf-throughput-${provider.name}`}>{formatRate(rate)}</td>
      <td data-testid={`saf-quarantined-${provider.name}`}>{provider.quarantined}</td>
      {showDiskCache ? (
        <td>
          {provider.storeAndForward ? (
            <button type="button" data-testid={`saf-archive-${provider.name}`} onClick={() => onArchive(provider.name)}>
              Archive
            </button>
          ) : null}
        </td>
      ) : null}
    </tr>
  );
});

export function StoreAndForwardPage({ section, client, session }: AdminPageProps) {
  const canRead = session ? allows(session, "gateway.read") : Boolean(client);
  const canWrite = Boolean(session && allows(session, "gateway.write"));
  const [reloadToken, setReloadToken] = useState(0);
  const [providerFilter, setProviderFilter] = useState("");
  const [selected, setSelected] = useState<Set<number>>(() => new Set());
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [importFile, setImportFile] = useState<File | null>(null);
  const [cacheFile, setCacheFile] = useState<File | null>(null);
  const importInput = useRef<HTMLInputElement>(null);
  const cacheInput = useRef<HTMLInputElement>(null);
  const { data, rates, error, updatedAt } = useStoreForward(client, canRead && Boolean(client), reloadToken);

  useEffect(() => {
    if (!data) return;
    const live = new Set(data.records.map((row) => row.id));
    setSelected((prev) => {
      let changed = false;
      const next = new Set<number>();
      for (const id of prev) {
        if (live.has(id)) next.add(id);
        else changed = true;
      }
      return changed ? next : prev;
    });
  }, [data]);

  const records = data?.records ?? [];
  const visible = providerFilter ? records.filter((row) => row.provider === providerFilter) : records;
  const exportIds = selected.size ? [...selected] : visible.map((row) => row.id);
  const canAct = canWrite && !data?.quarantineDenied && selected.size > 0 && !busy;

  const toggle = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    setSelected((prev) => {
      const next = new Set(prev);
      const allOn = visible.length > 0 && visible.every((row) => next.has(row.id));
      for (const row of visible) {
        if (allOn) next.delete(row.id);
        else next.add(row.id);
      }
      return next;
    });
  };

  const run = async (path: string, label: string) => {
    if (!client || selected.size === 0) return;
    setBusy(true);
    setNotice(null);
    setActionError(null);
    try {
      const res = await client.request("POST", path, { ids: [...selected] });
      const payload = (await res.json().catch(() => ({}))) as { detail?: unknown; retried?: unknown; deleted?: unknown };
      if (!res.ok) {
        const detail = typeof payload.detail === "string" ? payload.detail : "Could not update quarantine";
        throw new ApiError(detail, res.status);
      }
      const count = Number(payload.retried ?? payload.deleted ?? selected.size);
      setNotice(`${label} ${Number.isFinite(count) ? count : 0}.`);
      setSelected(new Set());
      setReloadToken((value) => value + 1);
    } catch (err: unknown) {
      setActionError(errorMessage(err, "Could not update quarantine"));
    } finally {
      setBusy(false);
    }
  };

  const problem = async (res: Response, fallback: string) => {
    const payload = (await res.json().catch(() => ({}))) as { detail?: unknown };
    const detail = typeof payload.detail === "string" ? payload.detail : fallback;
    throw new ApiError(detail, res.status);
  };

  const importQuarantine = async () => {
    if (!client || !importFile) return;
    setBusy(true);
    setNotice(null);
    setActionError(null);
    try {
      const text = await importFile.text();
      const trimmed = text.trimStart().replace(/^\uFEFF/, "");
      const contentType = trimmed.startsWith("{") ? "application/json" : "text/csv";
      const res = await client.postRaw(QUARANTINE_IMPORT_PATH, text, contentType);
      if (!res.ok) await problem(res, "Could not import quarantine");
      const payload = (await res.json()) as { retried?: unknown };
      const count = Number(payload.retried);
      setNotice(`Retried ${Number.isFinite(count) ? count : 0}.`);
      setImportFile(null);
      if (importInput.current) importInput.current.value = "";
      setReloadToken((value) => value + 1);
    } catch (err: unknown) {
      setActionError(errorMessage(err, "Could not import quarantine"));
    } finally {
      setBusy(false);
    }
  };

  const archiveCache = async (providerName: string) => {
    if (!client) return;
    const ok = window.confirm(
      `Archive the disk cache for ${providerName}? Archiving locks the cache. Samples that arrive during the archive wait, and the archived samples leave the live cache.`,
    );
    if (!ok) return;
    setBusy(true);
    setNotice(null);
    setActionError(null);
    try {
      const res = await client.request("POST", `${DISK_CACHE_ARCHIVE_PATH}?provider=${encodeURIComponent(providerName)}`);
      if (!res.ok) await problem(res, "Could not archive the disk cache");
      const payload = (await res.json()) as { records?: unknown };
      const count = Number(payload.records);
      setNotice(`Archived ${Number.isFinite(count) ? count : 0} records for ${providerName}.`);
      setReloadToken((value) => value + 1);
    } catch (err: unknown) {
      setActionError(errorMessage(err, "Could not archive the disk cache"));
    } finally {
      setBusy(false);
    }
  };

  const loadCache = async () => {
    if (!client || !cacheFile) return;
    setBusy(true);
    setNotice(null);
    setActionError(null);
    try {
      const text = await cacheFile.text();
      const contentType = text.includes("\n") ? "application/x-ndjson" : "application/json";
      const res = await client.postRaw(DISK_CACHE_LOAD_PATH, text, contentType);
      if (!res.ok) await problem(res, "Could not load the disk cache");
      const payload = (await res.json()) as { loaded?: unknown };
      const count = Number(payload.loaded);
      setNotice(`Loaded ${Number.isFinite(count) ? count : 0}.`);
      setCacheFile(null);
      if (cacheInput.current) cacheInput.current.value = "";
      setReloadToken((value) => value + 1);
    } catch (err: unknown) {
      setActionError(errorMessage(err, "Could not load the disk cache"));
    } finally {
      setBusy(false);
    }
  };

  const loadNamed = async (name: string) => {
    if (!client) return;
    setBusy(true);
    setNotice(null);
    setActionError(null);
    try {
      const res = await client.request("POST", DISK_CACHE_LOAD_PATH, { name });
      if (!res.ok) await problem(res, "Could not load the disk cache");
      const payload = (await res.json()) as { loaded?: unknown };
      const count = Number(payload.loaded);
      setNotice(`Loaded ${Number.isFinite(count) ? count : 0}.`);
      setReloadToken((value) => value + 1);
    } catch (err: unknown) {
      setActionError(errorMessage(err, "Could not load the disk cache"));
    } finally {
      setBusy(false);
    }
  };

  const deleteArchive = async (name: string) => {
    if (!client) return;
    setBusy(true);
    setNotice(null);
    setActionError(null);
    try {
      const res = await client.request("DELETE", `${DISK_CACHE_ARCHIVES_PATH}/${encodeURIComponent(name)}`);
      if (!res.ok && res.status !== 204) await problem(res, "Could not delete the archive");
      setNotice(`Deleted ${name}.`);
      setReloadToken((value) => value + 1);
    } catch (err: unknown) {
      setActionError(errorMessage(err, "Could not delete the archive"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      {!canRead ? (
        <p role="status" data-testid="saf-no-read">
          Your roles do not include gateway.read, so store-and-forward status is hidden.
        </p>
      ) : (
        <>
          <p className="lede">
            Pending records, cache occupancy, store rate, and forward throughput for each history provider, plus
            quarantined records and the alarm journal queue.
          </p>
          <p className="hint">
            Refreshes every 1 second. Buffer fill is the number of records waiting in that provider&apos;s durable
            cache. The cache has no record cap. Store rate is datapoints per second written into that disk cache over
            the last 10 seconds. Forward throughput is records forwarded per second since the previous refresh.
          </p>
          {session && !canWrite ? (
            <p className="read-only-note" data-testid="saf-read-only">
              Retry, delete, export, import, and disk-cache archive need gateway.write.
            </p>
          ) : null}
          {error ? <p role="alert">{error}</p> : null}
          {actionError ? <p role="alert">{actionError}</p> : null}
          {notice ? <p role="status">{notice}</p> : null}
          {!data && !error ? <p>Loading store-and-forward status.</p> : null}
          {data ? (
            <>
              <p data-testid="memory-buffer">
                Memory buffer {data.collector.pending} records. Dropped {data.collector.dropped}.
              </p>
              <table
                className="data-table"
                aria-label="Store and forward engines"
                data-testid="saf-engines"
                data-updated={updatedAt ?? ""}
              >
                <thead>
                  <tr>
                    <th>Provider</th>
                    <th>Status</th>
                    <th>Pending</th>
                    <th>Buffer fill</th>
                    <th>Store rate</th>
                    <th>Forward throughput</th>
                    <th>Quarantined</th>
                    {canWrite ? <th>Disk cache</th> : null}
                  </tr>
                </thead>
                <tbody>
                  {data.providers.map((provider) => (
                    <ProviderRow
                      key={provider.name}
                      provider={provider}
                      rate={rates[provider.name] ?? null}
                      showDiskCache={canWrite}
                      onArchive={(name) => void archiveCache(name)}
                    />
                  ))}
                </tbody>
              </table>

              <section aria-label="Disk cache">
                <h2>Disk cache</h2>
                <p className="hint">
                  Archive writes one provider&apos;s pending disk cache to a file on the gateway and then removes those
                  records from the live cache. Load puts a listed archive, or an uploaded copy of one, back on that
                  provider&apos;s queue.
                </p>
                <table className="data-table" aria-label="Disk cache archives" data-testid="saf-archives">
                  <thead>
                    <tr>
                      <th>Archive</th>
                      <th>Provider</th>
                      <th>Records</th>
                      <th>Created</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.archives.length === 0 ? (
                      <tr>
                        <td colSpan={5}>No disk-cache archives.</td>
                      </tr>
                    ) : (
                      data.archives.map((archive) => (
                        <tr key={archive.name} data-testid="saf-archive-row" data-provider={archive.provider}>
                          <td>{archive.name}</td>
                          <td>{archive.provider}</td>
                          <td>{archive.records}</td>
                          <td>{archive.created}</td>
                          <td>
                            <a href={`${DISK_CACHE_ARCHIVES_PATH}/${encodeURIComponent(archive.name)}`}>Download</a>
                            {canWrite ? (
                              <>
                                <button type="button" disabled={busy} onClick={() => void loadNamed(archive.name)}>
                                  Load
                                </button>
                                <button type="button" disabled={busy} onClick={() => void deleteArchive(archive.name)}>
                                  Delete archive
                                </button>
                              </>
                            ) : null}
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
                {canWrite ? (
                  <div className="inline-form">
                    <label>
                      Disk cache archive
                      <input
                        ref={cacheInput}
                        type="file"
                        accept="application/json,.json,application/x-ndjson"
                        data-testid="cache-load-file"
                        onChange={(event) => setCacheFile(event.target.files?.[0] ?? null)}
                      />
                    </label>
                    <button type="button" disabled={!cacheFile || busy} onClick={() => void loadCache()}>
                      Load disk cache
                    </button>
                  </div>
                ) : null}
              </section>

              <section aria-label="Quarantine">
                <h2>Quarantine</h2>
                {data.quarantineDenied ? (
                  <p role="status" data-testid="quarantine-denied">
                    Quarantine records need tags.read.
                  </p>
                ) : (
                  <>
                    <div className="inline-form">
                      <label>
                        Provider
                        <select
                          aria-label="Quarantine provider"
                          value={providerFilter}
                          onChange={(event) => setProviderFilter(event.target.value)}
                        >
                          <option value="">All</option>
                          {data.providers.map((provider) => (
                            <option key={provider.name} value={provider.name}>
                              {provider.name}
                            </option>
                          ))}
                        </select>
                      </label>
                      {canWrite ? (
                        <>
                          <button type="button" disabled={!canAct} onClick={() => void run("/api/v1/diagnostics/history/quarantine/retry", "Retried")}>
                            Retry
                          </button>
                          <button type="button" disabled={!canAct} onClick={() => void run("/api/v1/diagnostics/history/quarantine/delete", "Deleted")}>
                            Delete
                          </button>
                          <a href={quarantineExportPath({ format: "json", provider: providerFilter || undefined, ids: exportIds })} download="quarantine.json">
                            Export JSON
                          </a>
                          <a href={quarantineExportPath({ format: "csv", provider: providerFilter || undefined, ids: exportIds })} download="quarantine.csv">
                            Export CSV
                          </a>
                          <label>
                            Import quarantine file
                            <input
                              ref={importInput}
                              type="file"
                              accept=".json,.csv,application/json,text/csv"
                              data-testid="quarantine-import-file"
                              onChange={(event) => setImportFile(event.target.files?.[0] ?? null)}
                            />
                          </label>
                          <button type="button" disabled={!importFile || busy} onClick={() => void importQuarantine()}>
                            Import
                          </button>
                        </>
                      ) : null}
                    </div>
                    <table className="data-table" aria-label="Quarantined records">
                      <thead>
                        <tr>
                          {canWrite ? (
                            <th>
                              <input
                                type="checkbox"
                                aria-label="Select all quarantined records"
                                checked={visible.length > 0 && visible.every((row) => selected.has(row.id))}
                                onChange={toggleAll}
                              />
                            </th>
                          ) : null}
                          <th>Provider</th>
                          <th>Tag</th>
                          <th>Time</th>
                          <th>Value</th>
                          <th>Quality</th>
                          <th>Error</th>
                          <th>Quarantined</th>
                        </tr>
                      </thead>
                      <tbody>
                        {visible.length === 0 ? (
                          <tr>
                            <td colSpan={canWrite ? 8 : 7}>No quarantined records.</td>
                          </tr>
                        ) : (
                          visible.map((row) => (
                            <QuarantineRow
                              key={row.id}
                              row={row}
                              canWrite={canWrite}
                              checked={selected.has(row.id)}
                              onToggle={toggle}
                            />
                          ))
                        )}
                      </tbody>
                    </table>
                  </>
                )}
              </section>

              <section aria-label="Alarm journal">
                <h2>Alarm journal</h2>
                <dl className="meta">
                  <dt>Queued</dt>
                  <dd data-testid="journal-queued">{data.journal.queued}</dd>
                  <dt>Retrying</dt>
                  <dd data-testid="journal-retrying">{data.journal.retrying ? "yes" : "no"}</dd>
                  <dt>Lost rows</dt>
                  <dd data-testid="journal-lost">{data.journal.lostRows}</dd>
                </dl>
              </section>
            </>
          ) : null}
        </>
      )}
    </article>
  );
}

const QuarantineRow = memo(function QuarantineRow({
  row,
  canWrite,
  checked,
  onToggle,
}: {
  row: QuarantineRecord;
  canWrite: boolean;
  checked: boolean;
  onToggle: (id: number) => void;
}) {
  return (
    <tr>
      {canWrite ? (
        <td>
          <input type="checkbox" aria-label={`Select ${row.id}`} checked={checked} onChange={() => onToggle(row.id)} />
        </td>
      ) : null}
      <td>{row.provider}</td>
      <td>{row.tagPath}</td>
      <td>{row.timestamp}</td>
      <td>{formatValue(row.value)}</td>
      <td>{row.quality}</td>
      <td className="cell-wrap">{row.error}</td>
      <td>{row.quarantinedAt}</td>
    </tr>
  );
});
