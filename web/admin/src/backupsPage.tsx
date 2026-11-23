import { useEffect, useState } from "react";
import { allows, errorMessage } from "./access";
import { MIN_BACKUP_PASSPHRASE, type BackupItem } from "./api";
import type { AdminPageProps } from "./pages";
import { RESTORED_KEY } from "./restoredNotice";

/**
 * List, create, download, and restore gateway backups.
 * The archive contains secrets.key, so create, download, and restore need backups.admin (administrator only).
 * Every backup is encrypted under a passphrase entered here; the gateway does not keep it (review-1 F3).
 * A plaintext .zip stored by an older version is listed as not encrypted and has no download link.
 * Other readers see the list of names and sizes. The warning is the one in docs/ops.md.
 * (FEATURES GAT-014).
 */
export function BackupsPage({ section, client, session }: AdminPageProps) {
  const canAdmin = allows(session, "backups.admin");
  const [items, setItems] = useState<BackupItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [confirming, setConfirming] = useState<BackupItem | null>(null);
  const [passphrase, setPassphrase] = useState("");
  const [passphraseAgain, setPassphraseAgain] = useState("");
  const [restorePassphrase, setRestorePassphrase] = useState("");
  const tooShort = [...passphrase].length < MIN_BACKUP_PASSPHRASE;
  const mismatch = passphraseAgain !== passphrase;
  const [reloadToken, setReloadToken] = useState(0);
  const [file, setFile] = useState<File | null>(null);
  const [filePassphrase, setFilePassphrase] = useState("");
  const [confirmFile, setConfirmFile] = useState(false);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .listBackups()
      .then((next) => {
        if (cancelled) return;
        setItems(next);
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setItems(null);
        setError(errorMessage(err, "Could not list backups"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, reloadToken]);

  async function create() {
    if (!client || pending || tooShort || mismatch) return;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      const created = await client.createBackup(passphrase);
      setPassphrase("");
      setPassphraseAgain("");
      setNotice(`Created ${created.name}.`);
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not create a backup"));
    } finally {
      setPending(false);
    }
  }

  function openRestore(item: BackupItem) {
    setRestorePassphrase("");
    setConfirming(item);
  }

  function closeRestore() {
    setRestorePassphrase("");
    setConfirming(null);
  }

  async function restore(item: BackupItem) {
    if (!client || pending || (item.encrypted && restorePassphrase === "")) return;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      await client.restoreBackup(item.name, item.encrypted ? restorePassphrase : undefined);
      closeRestore();
      setNotice(`Restored ${item.name}.`);
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not restore the backup"));
    } finally {
      setPending(false);
    }
  }

  async function restoreFile() {
    if (!client || pending || !file) return;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      await client.restoreBackupFile(await file.arrayBuffer(), filePassphrase || undefined);
      // The restored users replace this session, so the app usually moves to sign-in next; it shows this there.
      try {
        window.sessionStorage.setItem(RESTORED_KEY, file.name);
      } catch {
        // storage off: the notice below is enough
      }
      setNotice(`Restored ${file.name}. Devices, tags, projects and users from the backup are live now.`);
      setFile(null);
      setFilePassphrase("");
      setConfirmFile(false);
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not restore the backup file"));
    } finally {
      setPending(false);
    }
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="banner banner-warn" role="status" data-testid="backup-warning">
        A gateway backup contains secrets.key and the secret store, encrypted under the passphrase you enter. Anyone who
        holds the file and its passphrase can decrypt the stored secrets. The gateway does not keep the passphrase: a lost
        passphrase cannot be recovered.
      </p>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {canAdmin ? (
        <form
          className="inline-form"
          onSubmit={(event) => {
            event.preventDefault();
            void create();
          }}
        >
          <label>
            Backup passphrase
            <input
              type="password"
              autoComplete="new-password"
              value={passphrase}
              minLength={MIN_BACKUP_PASSPHRASE}
              onChange={(event) => setPassphrase(event.target.value)}
              required
            />
          </label>
          <label>
            Confirm passphrase
            <input
              type="password"
              autoComplete="new-password"
              value={passphraseAgain}
              onChange={(event) => setPassphraseAgain(event.target.value)}
              required
            />
          </label>
          <button type="submit" disabled={pending || tooShort || mismatch}>
            Create backup
          </button>
          {passphrase !== "" && tooShort ? (
            <p className="muted">At least {MIN_BACKUP_PASSPHRASE} characters.</p>
          ) : null}
          {passphraseAgain !== "" && mismatch ? <p className="muted">The passphrases do not match.</p> : null}
        </form>
      ) : null}
      {!items && !error ? <p>Loading backups.</p> : null}
      {items ? (
        <table className="data-table" aria-label="Backups">
          <thead>
            <tr>
              <th>Name</th>
              <th>Bytes</th>
              <th>Encryption</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {items.length === 0 ? (
              <tr>
                <td colSpan={4}>No backups yet.</td>
              </tr>
            ) : null}
            {items.map((item) => (
              <tr key={item.name}>
                <td>{item.name}</td>
                <td>{item.bytes}</td>
                <td>{item.encrypted ? "Encrypted" : "Not encrypted (older version, not downloadable)"}</td>
                <td className="row-actions">
                  {canAdmin && item.encrypted ? (
                    <a href={`/api/v1/backups/${encodeURIComponent(item.name)}`} download={item.name}>
                      Download {item.name}
                    </a>
                  ) : null}
                  {canAdmin ? (
                    <button type="button" disabled={pending} onClick={() => openRestore(item)}>
                      Restore {item.name}
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {canAdmin ? (
        <form
          className="inline-form"
          aria-label="Restore from a backup file"
          onSubmit={(event) => {
            event.preventDefault();
            setConfirmFile(true);
          }}
        >
          <h2>Restore from a backup file</h2>
          <p className="muted">
            Use a .oipbk file downloaded from this or another gateway, for example to move a project to a new machine or to
            recover after a reinstall.
          </p>
          <label>
            Backup file
            <input type="file" accept=".oipbk,.zip" onChange={(event) => setFile(event.target.files?.[0] ?? null)} required />
          </label>
          <label>
            Passphrase for the backup file
            <input
              type="password"
              autoComplete="off"
              value={filePassphrase}
              onChange={(event) => setFilePassphrase(event.target.value)}
            />
          </label>
          <button type="submit" disabled={pending || !file}>
            Restore from file
          </button>
          {confirmFile && file ? (
            <div role="dialog" aria-label="Confirm restore from file" className="confirm-panel">
              <p>This replaces the running gateway configuration with {file.name}. Confirm to continue.</p>
              <button type="button" disabled={pending} onClick={() => void restoreFile()}>
                Restore now
              </button>
              <button type="button" disabled={pending} onClick={() => setConfirmFile(false)}>
                Cancel
              </button>
            </div>
          ) : null}
        </form>
      ) : null}
      {confirming ? (
        <div role="dialog" aria-labelledby="restore-title" className="confirm-panel">
          <h2 id="restore-title">Restore backup</h2>
          <p>This replaces the running gateway configuration with {confirming.name}. Confirm to continue.</p>
          <div className="inline-form">
            {confirming.encrypted ? (
              <label>
                Restore passphrase
                <input
                  type="password"
                  autoComplete="off"
                  value={restorePassphrase}
                  onChange={(event) => setRestorePassphrase(event.target.value)}
                  required
                />
              </label>
            ) : null}
            <button
              type="button"
              disabled={pending || (confirming.encrypted && restorePassphrase === "")}
              onClick={() => void restore(confirming)}
            >
              Confirm restore
            </button>
            <button type="button" disabled={pending} onClick={closeRestore}>
              Cancel
            </button>
          </div>
        </div>
      ) : null}
    </article>
  );
}
