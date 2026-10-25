import { useState } from "react";
import { api, type Chunk, type Guard, type Job, type Me } from "../api";
import { ErrorNote, Progress, StateBadge } from "../components";
import { ago, duration, eta, formatBytes, formatNumber, usePoll } from "../util";
import { canCancel } from "./Jobs";

/** Job detail: state, wait reason, per-shard progress, freshness, ETA, chunks (SPEC §7.1 step 5). */
export function JobDetailPage({ id, me }: { id: string; me: Me }) {
  const [error, setError] = useState<string | null>(null);
  const job = usePoll<Job>(() => api.job(id), 2000, [id]);
  const chunks = usePoll<Chunk[]>(() => api.chunks(id), 3000, [id]);
  const guard = usePoll<Guard>(() => api.guard(), 5000);
  const j = job.data;

  if (!j) {
    return (
      <>
        <h1 tabIndex={-1}>Job {id}</h1>
        <ErrorNote error={job.error} />
        {!job.error && <p role="status">Loading…</p>}
      </>
    );
  }

  const byShard = new Map<string, { done: number; total: number; preemptions: number }>();
  for (const c of chunks.data ?? []) {
    const s = byShard.get(c.shard) ?? { done: 0, total: 0, preemptions: 0 };
    s.total++;
    if (c.state === "DONE") s.done++;
    s.preemptions += c.preemptions;
    byShard.set(c.shard, s);
  }

  return (
    <>
      <h1 tabIndex={-1}>Job {j.id}</h1>
      <p>
        <a href="#/jobs">← Jobs</a>
      </p>
      <ErrorNote error={error} />
      <section className="card" aria-labelledby="summary-heading">
        <h2 id="summary-heading">Summary</h2>
        <dl className="facts">
          <dt>State</dt>
          <dd>
            <span role="status" aria-live="polite">
              <StateBadge job={j} /> {j.wait_reason && <span className="muted">{j.wait_reason}</span>}
              {j.reason && <span className="muted"> {j.reason}</span>}
            </span>
          </dd>
          <dt>What</dt>
          <dd>{j.dataset ? `${j.dataset} (${j.tables.join(", ")})` : j.tables.join(", ")}</dd>
          <dt>COB dates</dt>
          <dd>{j.cob_dates.join(", ") || "— (full copy)"}</dd>
          <dt>Target</dt>
          <dd>{j.target}</dd>
          <dt>Requested by</dt>
          <dd>
            {j.requesters.join(", ")} · {ago(j.created_at)}
          </dd>
          {j.queue_position != null && (
            <>
              <dt>Jobs ahead in the queue</dt>
              <dd>{j.queue_position}</dd>
            </>
          )}
          <dt>Progress</dt>
          <dd>
            <Progress done={j.progress.chunks_done} total={j.progress.chunks_total} label="Chunks copied" /> · partitions {j.progress.partitions_done}/{j.progress.partitions_total} ·{" "}
            {formatNumber(j.progress.rows_copied)} rows
          </dd>
          <dt>Estimated size</dt>
          <dd>{formatBytes(j.estimated_bytes)}</dd>
          <dt>ETA</dt>
          <dd>{eta(j)}</dd>
          <dt>Data freshness</dt>
          <dd>{guard.data?.replica_delay_seconds != null ? `DR is ${duration(guard.data.replica_delay_seconds)} behind production` : "—"}</dd>
          <dt>Config version</dt>
          <dd>
            <code>{j.config_version}</code>
          </dd>
        </dl>
        {canCancel(me, j) && (
          <button
            type="button"
            onClick={() =>
              api
                .cancel(j.id)
                .then(() => job.reload())
                .catch((e: Error) => setError(e.message))
            }
          >
            Cancel job
          </button>
        )}
      </section>

      {byShard.size > 0 && (
        <section aria-labelledby="shards-heading">
          <h2 id="shards-heading">Per DR shard</h2>
          <table>
            <caption className="sr-only">Chunk progress per DR shard</caption>
            <thead>
              <tr>
                <th scope="col">Shard</th>
                <th scope="col">Chunks</th>
                <th scope="col">Pre-emptions</th>
              </tr>
            </thead>
            <tbody>
              {[...byShard.entries()].map(([shard, s]) => (
                <tr key={shard}>
                  <td>{shard}</td>
                  <td>
                    <Progress done={s.done} total={s.total} label={`Chunks copied from shard ${shard}`} />
                  </td>
                  <td>{s.preemptions}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {(chunks.data?.length ?? 0) > 0 && (
        <details>
          <summary>Chunks ({chunks.data!.length})</summary>
          <table>
            <caption className="sr-only">Chunks of this job</caption>
            <thead>
              <tr>
                <th scope="col">Table</th>
                <th scope="col">Shard</th>
                <th scope="col">Partition</th>
                <th scope="col">State</th>
                <th scope="col">Attempts</th>
                <th scope="col">Rows</th>
                <th scope="col">Note</th>
              </tr>
            </thead>
            <tbody>
              {chunks.data!.map((c) => (
                <tr key={c.id}>
                  <td>{c.table}</td>
                  <td>{c.shard}</td>
                  <td>{c.partition_id}</td>
                  <td>{c.state}</td>
                  <td>{c.attempts}</td>
                  <td>{c.rows != null ? formatNumber(c.rows) : "—"}</td>
                  <td>{c.wait_reason ?? c.error ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      )}
    </>
  );
}
