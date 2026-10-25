import { useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError, type Catalog, type Estimate, type SubmitBody } from "../api";
import { ErrorNote } from "../components";
import { ago, formatBytes, formatNumber, newKey } from "../util";

type Source = "dataset" | "table";
type CobMode = "dates" | "range";

/** SPEC §7.1: dataset or table → COB date(s) → shared target → estimate (with last-copied info) → submit. */
export function NewRequestPage() {
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [source, setSource] = useState<Source>("dataset");
  const [dataset, setDataset] = useState("");
  const [tables, setTables] = useState<string[]>([]);
  const [cobMode, setCobMode] = useState<CobMode>("dates");
  const [dates, setDates] = useState<string[]>([]);
  const [dateInput, setDateInput] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [target, setTarget] = useState("");
  const [mode, setMode] = useState<"replace" | "skip_if_present">("replace");
  const [estimate, setEstimate] = useState<Estimate | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const idempotencyKey = useRef(newKey());

  useEffect(() => {
    api
      .catalog()
      .then((c) => {
        setCatalog(c);
        setDataset(c.datasets[0]?.name ?? "");
        setTarget(c.targets[0] ?? "");
        if (c.datasets.length === 0) setSource("table");
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  const selectedTables = useMemo(() => {
    if (!catalog) return [];
    return source === "dataset" ? (catalog.datasets.find((d) => d.name === dataset)?.tables ?? []) : tables;
  }, [catalog, source, dataset, tables]);
  const needsCob = !!catalog && selectedTables.some((t) => catalog.tables.find((x) => x.name === t)?.full_copy === false);

  // Any change invalidates the estimate, the confirmation and the idempotency key.
  useEffect(() => {
    setEstimate(null);
    setConfirm(false);
    idempotencyKey.current = newKey();
  }, [source, dataset, tables, cobMode, dates, from, to, target, mode]);

  function body(): SubmitBody {
    const cob = !needsCob ? undefined : cobMode === "dates" ? { dates } : { from, to };
    return {
      ...(source === "dataset" ? { dataset } : { tables }),
      cob,
      target,
      mode,
      confirm_overwrite_recent: confirm,
    };
  }

  function validate(): string | null {
    if (selectedTables.length === 0) return "Choose a dataset or at least one table.";
    if (needsCob && cobMode === "dates" && dates.length === 0) return "Add at least one COB date.";
    if (needsCob && cobMode === "range" && (!from || !to)) return "Give both ends of the COB range.";
    if (!target) return "Choose a target environment.";
    return null;
  }

  async function runEstimate() {
    const problem = validate();
    if (problem) return setError(problem);
    setBusy(true);
    setError(null);
    try {
      setEstimate(await api.estimate(body()));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function submit() {
    const problem = validate();
    if (problem) return setError(problem);
    setBusy(true);
    setError(null);
    try {
      const job = await api.submit(body(), idempotencyKey.current);
      location.hash = `#/jobs/${encodeURIComponent(job.id)}`;
    } catch (e) {
      if (e instanceof ApiError && e.code === "confirm_overwrite_recent") {
        setError(`${e.message}. Tick the confirmation below to replace it.`);
        if (!estimate) setEstimate(await api.estimate(body()).catch(() => null));
      } else {
        setError((e as Error).message);
      }
    } finally {
      setBusy(false);
    }
  }

  if (!catalog) {
    return (
      <>
        <h1 tabIndex={-1}>New copy request</h1>
        <ErrorNote error={error} />
        {!error && <p role="status">Loading the catalog…</p>}
      </>
    );
  }
  const recent = estimate?.recent_copies ?? [];
  const mustConfirm = !!estimate?.confirmation_required;

  return (
    <>
      <h1 tabIndex={-1}>New copy request</h1>
      <p className="muted">Masked data is copied from the passive DR cluster. Nothing runs against production.</p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <fieldset>
          <legend>What to copy</legend>
          <div className="radios">
            <label>
              <input type="radio" name="source" checked={source === "dataset"} onChange={() => setSource("dataset")} disabled={catalog.datasets.length === 0} /> A dataset
            </label>
            <label>
              <input type="radio" name="source" checked={source === "table"} onChange={() => setSource("table")} /> Individual tables
            </label>
          </div>
          {source === "dataset" ? (
            <div className="field">
              <label htmlFor="dataset">Dataset</label>
              <select id="dataset" value={dataset} onChange={(e) => setDataset(e.target.value)}>
                {catalog.datasets.map((d) => (
                  <option key={d.name} value={d.name}>
                    {d.name}
                    {d.description ? ` — ${d.description}` : ""}
                  </option>
                ))}
              </select>
              <p className="hint">Tables: {selectedTables.join(", ")}</p>
            </div>
          ) : (
            <div className="field">
              <label htmlFor="tables">Tables</label>
              <select id="tables" multiple size={Math.min(6, catalog.tables.length)} value={tables} onChange={(e) => setTables(Array.from(e.target.selectedOptions, (o) => o.value))}>
                {catalog.tables.map((t) => (
                  <option key={t.name} value={t.name}>
                    {t.name}
                    {t.full_copy ? " (full copy)" : ""}
                  </option>
                ))}
              </select>
            </div>
          )}
        </fieldset>

        {needsCob && (
          <fieldset>
            <legend>COB dates</legend>
            <div className="radios">
              <label>
                <input type="radio" name="cobmode" checked={cobMode === "dates"} onChange={() => setCobMode("dates")} /> Specific dates
              </label>
              <label>
                <input type="radio" name="cobmode" checked={cobMode === "range"} onChange={() => setCobMode("range")} /> Range
              </label>
            </div>
            {cobMode === "dates" ? (
              <div className="field">
                <label htmlFor="cob">COB date</label>
                <span className="inline">
                  <input id="cob" type="date" value={dateInput} onChange={(e) => setDateInput(e.target.value)} />
                  <button
                    type="button"
                    onClick={() => {
                      if (dateInput && !dates.includes(dateInput)) setDates([...dates, dateInput].sort());
                    }}
                  >
                    Add date
                  </button>
                </span>
                {dates.length > 0 && (
                  <ul className="chips" aria-label="Selected COB dates">
                    {dates.map((d) => (
                      <li key={d}>
                        {d}{" "}
                        <button type="button" className="link" aria-label={`Remove ${d}`} onClick={() => setDates(dates.filter((x) => x !== d))}>
                          ×
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            ) : (
              <div className="field inline">
                <label htmlFor="from">From</label>
                <input id="from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
                <label htmlFor="to">To</label>
                <input id="to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
              </div>
            )}
          </fieldset>
        )}

        <fieldset>
          <legend>Where to</legend>
          <div className="field">
            <label htmlFor="target">Target environment</label>
            <select id="target" value={target} onChange={(e) => setTarget(e.target.value)}>
              {catalog.targets.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
            <p className="hint">Targets are shared: your copy replaces the selected slice for everyone.</p>
          </div>
          <div className="field">
            <label htmlFor="mode">Mode</label>
            <select id="mode" value={mode} onChange={(e) => setMode(e.target.value as typeof mode)}>
              <option value="replace">Replace the selected slice</option>
              <option value="skip_if_present">Skip partitions already present</option>
            </select>
          </div>
        </fieldset>

        <div className="actions">
          <button type="button" onClick={() => void runEstimate()} disabled={busy}>
            Estimate
          </button>
          <button type="submit" className="primary" disabled={busy || (mustConfirm && !confirm)}>
            Submit request
          </button>
        </div>
        <ErrorNote error={error} />

        {estimate && (
          <section aria-labelledby="estimate-heading" className="card">
            <h2 id="estimate-heading">Estimate</h2>
            <table>
              <caption className="sr-only">Estimated size per table</caption>
              <thead>
                <tr>
                  <th scope="col">Table</th>
                  <th scope="col">Partitions</th>
                  <th scope="col">Rows</th>
                  <th scope="col">Size</th>
                </tr>
              </thead>
              <tbody>
                {estimate.tables.map((t) => (
                  <tr key={t.table}>
                    <td>{t.table}</td>
                    <td>{t.partitions}</td>
                    <td>{formatNumber(t.rows)}</td>
                    <td>{formatBytes(t.bytes)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p>
              Total {formatBytes(estimate.bytes)} · {estimate.queued_jobs} ad-hoc job(s) ahead in the queue.
            </p>
            <p className="hint">Sizes count whole partitions, so they are an upper bound when a partition holds several COB dates.</p>
            {!estimate.source_available && <p className="warn">The DR cluster is busy right now ({estimate.source_reason}); the copy will wait until it is free.</p>}
            <h3>Last copied</h3>
            {recent.length === 0 ? (
              <p className="muted">This slice has not been copied to {target} in the last 7 days.</p>
            ) : (
              <>
                <ul className="recent">
                  {recent.map((r) => (
                    <li key={`${r.table}-${r.cob_date}-${r.job_id}`}>
                      <strong>{r.table}</strong>
                      {r.cob_date ? ` COB ${r.cob_date}` : ""} on {target} was last replaced by <strong>{r.requester}</strong> {ago(r.copied_at)} (job {r.job_id}).
                    </li>
                  ))}
                </ul>
                {mustConfirm && (
                  <label className="confirm">
                    <input type="checkbox" checked={confirm} onChange={(e) => setConfirm(e.target.checked)} /> I understand this replaces data others copied recently
                  </label>
                )}
              </>
            )}
          </section>
        )}
      </form>
    </>
  );
}
