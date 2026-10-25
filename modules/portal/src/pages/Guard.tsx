import { api, type Guard } from "../api";
import { ErrorNote } from "../components";
import { duration, usePoll } from "../util";

/** Source Guard status per DR host and the active blocking reasons, never query text (SPEC §7.5). */
export function GuardPage() {
  const { data, error } = usePoll<Guard>(() => api.guard(), 3000);
  return (
    <>
      <h1 tabIndex={-1}>Source guard</h1>
      <p className="muted">Replication reads run only while the passive DR cluster is healthy and idle enough. Production is never contacted.</p>
      <ErrorNote error={error} />
      {data && (
        <>
          <div className="card" role="status" aria-live="polite">
          <dl className="facts">
            <dt>Cluster level</dt>
            <dd>
              <span className={`badge badge-level-${(data.level ?? "unguarded").toLowerCase()}`}>{data.guarded ? data.level : "UNGUARDED (test source)"}</span>
            </dd>
            <dt>Active replication reads</dt>
            <dd>{data.active_reads}</dd>
            <dt>DR replication delay</dt>
            <dd>{duration(data.replica_delay_seconds)}</dd>
            <dt>Global pause</dt>
            <dd>{data.paused ? "Paused" : "Not paused"}</dd>
            <dt>Failover flag</dt>
            <dd>{data.failover ? `Set: ${data.failover.message}` : "Not set"}</dd>
          </dl>
          </div>
          {Object.keys(data.hosts).length > 0 && (
            <table>
              <caption>DR hosts</caption>
              <thead>
                <tr>
                  <th scope="col">Host</th>
                  <th scope="col">Level</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(data.hosts).map(([host, level]) => (
                  <tr key={host}>
                    <td>{host}</td>
                    <td>{level}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <h2>Blocking reasons</h2>
          {data.reasons.length === 0 ? (
            <p className="muted">None.</p>
          ) : (
            <ul>
              {data.reasons.map((r, i) => (
                <li key={i}>
                  <code>{r.code}</code> {r.host ? `${r.host}: ` : ""}
                  {r.message}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </>
  );
}
