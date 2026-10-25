import { useState } from "react";
import { api, hasRole, type Me, type Stream } from "../api";
import { ErrorNote } from "../components";
import { ago, duration, usePoll } from "../util";

/** Continuous jobs: last success, lag against the SLO, state; pause/resume for operators (SPEC §8.4). */
export function ContinuousPage({ me }: { me: Me }) {
  const { data, error: loadError, reload } = usePoll<Stream[]>(() => api.continuous(), 5000);
  const [error, setError] = useState<string | null>(null);
  const operator = hasRole(me, "operator");
  const names = [...new Set((data ?? []).map((s) => s.name))];

  async function toggle(name: string, paused: boolean) {
    try {
      await (paused ? api.resumeStream(name) : api.pauseStream(name));
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <h1 tabIndex={-1}>Continuous replication</h1>
      <ErrorNote error={error ?? loadError} />
      {data && data.length === 0 && <p className="muted">No continuous jobs are configured.</p>}
      {data && data.length > 0 && (
        <table>
          <caption className="sr-only">Continuous streams</caption>
          <thead>
            <tr>
              <th scope="col">Job</th>
              <th scope="col">Table → target</th>
              <th scope="col">Mode</th>
              <th scope="col">Status</th>
              <th scope="col">Lag (SLO)</th>
              <th scope="col">Last success</th>
            </tr>
          </thead>
          <tbody>
            {data.map((s) => (
              <tr key={`${s.name}-${s.table}-${s.target}`} className={s.alerting ? "row-alert" : undefined}>
                <td>{s.name}</td>
                <td>
                  {s.table} → {s.target}
                </td>
                <td>{s.mode.replace(/_/g, " ")}</td>
                <td>
                  {s.status}
                  {(s.wait_reason || s.last_error) && <div className="muted">{s.wait_reason ?? s.last_error}</div>}
                </td>
                <td>
                  {duration(s.lag_seconds)} {s.lag_slo_seconds != null && <span className="muted">({duration(s.lag_slo_seconds)})</span>}
                  {s.slo_breached && <span className="badge badge-failed"> SLO breached</span>}
                </td>
                <td>{ago(s.last_success_at)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {operator && names.length > 0 && (
        <section aria-labelledby="ops-heading">
          <h2 id="ops-heading">Pause or resume</h2>
          <ul className="plain">
            {names.map((name) => {
              const paused = data!.some((s) => s.name === name && s.paused);
              return (
                <li key={name}>
                  <button type="button" onClick={() => void toggle(name, paused)}>
                    {paused ? `Resume ${name}` : `Pause ${name}`}
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      )}
    </>
  );
}
