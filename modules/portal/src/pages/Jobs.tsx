import { useState } from "react";
import { api, hasRole, TERMINAL, type Job, type Me } from "../api";
import { ErrorNote, Progress, StateBadge } from "../components";
import { ago, usePoll } from "../util";

export function canCancel(me: Me, job: Job): boolean {
  return !TERMINAL.includes(job.state) && job.state !== "CANCELLING" && (job.requester === me.user || hasRole(me, "operator"));
}

/** My jobs / all jobs (SPEC §7.1 step 5): state, queue position, wait reason, progress, cancel. */
export function JobsPage({ me }: { me: Me }) {
  const [mine, setMine] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { data, error: loadError, reload } = usePoll<Job[]>(() => api.jobs(mine), 3000, [mine]);

  async function cancel(job: Job) {
    try {
      await api.cancel(job.id);
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <h1 tabIndex={-1}>{mine ? "My jobs" : "All jobs"}</h1>
      <label className="toggle">
        <input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Only jobs I requested
      </label>
      <ErrorNote error={error ?? loadError} />
      {data && data.length === 0 && <p className="muted">No jobs yet.</p>}
      {data && data.length > 0 && (
        <table className="jobs">
          <caption className="sr-only">{mine ? "Jobs you requested" : "All jobs"}, newest first</caption>
          <thead>
            <tr>
              <th scope="col">Job</th>
              <th scope="col">What</th>
              <th scope="col">COB</th>
              <th scope="col">Target</th>
              <th scope="col">State</th>
              <th scope="col">Progress</th>
              <th scope="col">Requested</th>
              <th scope="col">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {data.map((job) => (
              <tr key={job.id}>
                <td>
                  <a href={`#/jobs/${encodeURIComponent(job.id)}`}>{job.id}</a>
                </td>
                <td>{job.dataset ?? job.tables.join(", ")}</td>
                <td>{job.cob_dates.length > 2 ? `${job.cob_dates[0]} … (${job.cob_dates.length})` : job.cob_dates.join(", ") || "—"}</td>
                <td>{job.target}</td>
                <td>
                  <StateBadge job={job} />
                  {job.queue_position != null && <div className="muted">{job.queue_position} ahead in the queue</div>}
                  {job.wait_reason && <div className="muted">{job.wait_reason}</div>}
                  {job.reason && <div className="muted">{job.reason}</div>}
                </td>
                <td>
                  <Progress done={job.progress.chunks_done} total={job.progress.chunks_total} label={`Chunks copied for job ${job.id}`} />
                </td>
                <td>
                  {job.requester}
                  <div className="muted">{ago(job.created_at)}</div>
                </td>
                <td>
                  {canCancel(me, job) && (
                    <button type="button" onClick={() => void cancel(job)} aria-label={`Cancel job ${job.id}`}>
                      Cancel
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
