import { useEffect, useState } from "react";

interface ApiRow {
  path: string;
  methods: string;
}

/** WEB-004: the generated API listing, as path and method, from /api/docs. */
export function ApiListing() {
  const [rows, setRows] = useState<ApiRow[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/docs")
      .then((res) => res.json())
      .then((doc: { paths?: Record<string, Record<string, unknown>> }) => {
        if (cancelled) return;
        const paths = doc.paths ?? {};
        setRows(Object.keys(paths).sort().map((path) => ({
          path,
          methods: Object.keys(paths[path] ?? {}).filter((key) => key !== "parameters").join(", ").toUpperCase(),
        })));
      })
      .catch(() => {
        if (!cancelled) setError("The API listing could not be loaded.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <section aria-label="API listing">
      <h2>API listing</h2>
      <p>This is the generated list of gateway routes. It is the same document served at /api/docs.</p>
      {error ? <p role="alert">{error}</p> : null}
      <table className="data-table" aria-label="Generated routes">
        <thead>
          <tr>
            <th>Path</th>
            <th>Methods</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.path}>
              <td>{row.path}</td>
              <td>{row.methods}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
