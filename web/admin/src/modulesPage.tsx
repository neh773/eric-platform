import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import type { AvailableModule, ModuleInfo } from "./api";
import type { AdminPageProps } from "./pages";

/**
 * Install, upgrade, or remove a module package. The gateway checks the manifest
 * before it activates the module. A built-in driver that was uninstalled stays
 * uninstalled across restarts and is listed under available modules until it is
 * installed again here.
 * (FEATURES GAT-001).
 */
export function ModulesPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "modules.admin");
  const [items, setItems] = useState<ModuleInfo[] | null>(null);
  const [available, setAvailable] = useState<AvailableModule[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [packageFile, setPackageFile] = useState<File | null>(null);
  const [upgrades, setUpgrades] = useState<Record<string, File | null>>({});
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    Promise.all([client.listModules(), client.listAvailableModules()])
      .then(([next, removed]) => {
        if (!cancelled) {
          setItems(next);
          setAvailable(removed);
          setError(null);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setItems(null);
          setError(errorMessage(err, "Could not list modules"));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [client, reloadToken]);

  async function install(event: FormEvent) {
    event.preventDefault();
    if (!client || !packageFile || pending) return;
    setPending(true);
    setError(null);
    try {
      await client.installModule(await packageFile.arrayBuffer());
      setPackageFile(null);
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not install the module"));
    } finally {
      setPending(false);
    }
  }

  async function installBuiltIn(moduleInfo: AvailableModule) {
    if (!client || pending) return;
    setPending(true);
    setError(null);
    try {
      await client.installBuiltInModule(moduleInfo.id);
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not install the module"));
    } finally {
      setPending(false);
    }
  }

  async function upgrade(moduleInfo: ModuleInfo) {
    const file = upgrades[moduleInfo.id];
    if (!client || !file || pending) return;
    setPending(true);
    setError(null);
    try {
      await client.upgradeModule(moduleInfo.id, await file.arrayBuffer());
      setUpgrades((current) => ({ ...current, [moduleInfo.id]: null }));
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not upgrade the module"));
    } finally {
      setPending(false);
    }
  }

  async function uninstall(moduleInfo: ModuleInfo) {
    if (!client || pending) return;
    setPending(true);
    setError(null);
    try {
      await client.uninstallModule(moduleInfo.id);
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not uninstall the module"));
    } finally {
      setPending(false);
    }
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="lede">Install, upgrade, or remove a module package. The gateway checks the manifest before activating it.</p>
      {error ? <p role="alert">{error}</p> : null}
      {!client ? <p role="alert">Modules are unavailable.</p> : null}
      {canWrite ? (
        <form className="inline-form" aria-label="Install module" onSubmit={(event) => void install(event)}>
          <label>
            Module package
            <input
              type="file"
              accept=".zip,application/zip"
              onChange={(event) => setPackageFile(event.target.files?.[0] ?? null)}
            />
          </label>
          <button type="submit" disabled={pending || !packageFile}>
            Install
          </button>
        </form>
      ) : null}
      {!items && !error ? <p>Loading modules.</p> : null}
      {items ? (
        <table className="data-table" aria-label="Installed modules">
          <thead>
            <tr>
              <th>Name</th>
              <th>Version</th>
              <th>State</th>
              <th>Id</th>
              {canWrite ? <th>Actions</th> : null}
            </tr>
          </thead>
          <tbody>
            {items.length === 0 ? (
              <tr>
                <td colSpan={canWrite ? 5 : 4}>No modules are installed.</td>
              </tr>
            ) : null}
            {items.map((moduleInfo) => (
              <tr key={moduleInfo.id}>
                <td>{moduleInfo.name}</td>
                <td>{moduleInfo.version}</td>
                <td>{moduleInfo.state || "unknown"}</td>
                <td>{moduleInfo.id}</td>
                {canWrite ? (
                  <td className="row-actions">
                    <label>
                      Upgrade package for {moduleInfo.name}
                      <input
                        type="file"
                        accept=".zip,application/zip"
                        aria-label={`Upgrade package for ${moduleInfo.name}`}
                        onChange={(event) =>
                          setUpgrades((current) => ({ ...current, [moduleInfo.id]: event.target.files?.[0] ?? null }))
                        }
                      />
                    </label>
                    <button type="button" disabled={pending || !upgrades[moduleInfo.id]} onClick={() => void upgrade(moduleInfo)}>
                      Upgrade {moduleInfo.name}
                    </button>
                    <button type="button" disabled={pending} onClick={() => void uninstall(moduleInfo)}>
                      Uninstall {moduleInfo.name}
                    </button>
                    {moduleInfo.error ? <span role="alert">{moduleInfo.error}</span> : null}
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {items && available.length > 0 ? (
        <table className="data-table" aria-label="Available built-in modules">
          <caption>Built-in modules that were uninstalled. They stay uninstalled across restarts until installed again.</caption>
          <thead>
            <tr>
              <th>Name</th>
              <th>Version</th>
              <th>Id</th>
              {canWrite ? <th>Actions</th> : null}
            </tr>
          </thead>
          <tbody>
            {available.map((moduleInfo) => (
              <tr key={moduleInfo.id}>
                <td>{moduleInfo.name}</td>
                <td>{moduleInfo.version}</td>
                <td>{moduleInfo.id}</td>
                {canWrite ? (
                  <td className="row-actions">
                    <button type="button" disabled={pending} onClick={() => void installBuiltIn(moduleInfo)}>
                      Install {moduleInfo.name}
                    </button>
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </article>
  );
}
