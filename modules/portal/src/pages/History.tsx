import { useEffect, useState } from "react";
import { api, type Catalog, type RecentCopy } from "../api";
import { ErrorNote } from "../components";
import { ago, formatNumber } from "../util";

/** The "last copied" registry of a shared target (SPEC §7.3). */
export function HistoryPage() {
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [target, setTarget] = useState("");
  const [table, setTable] = useState("");
  const [cob, setCob] = useState("");
  const [rows, setRows] = useState<RecentCopy[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .catalog()
      .then((c) => {
        setCatalog(c);
        setTarget(c.targets[0] ?? "");
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(() => {
    if (!target) return;
    api
      .history(target, table || undefined, cob || undefined)
      .then(setRows)
      .catch((e: Error) => setError(e.message));
  }, [target, table, cob]);

  return (
    <>
      <h1 tabIndex={-1}>Target history</h1>
      <p className="muted">Who last replaced which slice of a shared target.</p>
      <div className="filters">
        <div className="field">
          <label htmlFor="h-target">Target</label>
          <select id="h-target" value={target} onChange={(e) => setTarget(e.target.value)}>
            {catalog?.targets.map((t) => (
              <option key={t}>{t}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="h-table">Table</label>
          <select id="h-table" value={table} onChange={(e) => setTable(e.target.value)}>
            <option value="">All tables</option>
            {catalog?.tables.map((t) => (
              <option key={t.name}>{t.name}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="h-cob">COB date</label>
          <input id="h-cob" type="date" value={cob} onChange={(e) => setCob(e.target.value)} />
        </div>
      </div>
      <ErrorNote error={error} />
      {rows && rows.length === 0 && <p className="muted">Nothing copied to {target} matches.</p>}
      {rows && rows.length > 0 && (
        <table>
          <caption className="sr-only">Copies to {target}, newest first</caption>
          <thead>
            <tr>
              <th scope="col">Table</th>
              <th scope="col">COB</th>
              <th scope="col">Copied by</th>
              <th scope="col">When</th>
              <th scope="col">Job</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.job_id}-${r.table}-${r.cob_date}-${i}`}>
                <td>{r.table}</td>
                <td>{r.cob_date ?? "—"}</td>
                <td>{r.requester}</td>
                <td title={r.copied_at}>{ago(r.copied_at)}</td>
                <td>
                  <a href={`#/jobs/${encodeURIComponent(r.job_id)}`}>{r.job_id}</a>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {rows && rows.length >= 200 && <p className="muted">Showing the newest {formatNumber(200)} entries.</p>}
    </>
  );
}
