import { useState } from "react";
import { api, hasRole, type Guard, type Me } from "../api";
import { ErrorNote } from "../components";
import { usePoll } from "../util";

/** Operator and admin actions (SPEC §7.4): global pause, DC failover flag, config reload. */
export function AdminPage({ me }: { me: Me }) {
  const { data, reload } = usePoll<Guard>(() => api.guard(), 3000);
  const [note, setNote] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const admin = hasRole(me, "admin");
  const operator = hasRole(me, "operator");

  async function run(action: () => Promise<unknown>, done: string) {
    setError(null);
    setMessage(null);
    try {
      await action();
      setMessage(done);
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <h1 tabIndex={-1}>Operations</h1>
      <ErrorNote error={error} />
      {message && (
        <p role="status" className="ok">
          {message}
        </p>
      )}
      <div className="field">
        <label htmlFor="note">Note for the audit log</label>
        <input id="note" type="text" value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. DC switch rehearsal, INC-1234" />
      </div>
      {operator && (
        <section className="card" aria-labelledby="failover-heading">
          <h2 id="failover-heading">DC failover flag</h2>
          <p>Set this as the first step of a DC failover: no replication reads run at all until it is cleared.</p>
          <p>Currently: {data?.failover ? <strong>set — {data.failover.message}</strong> : "not set"}</p>
          {data?.failover ? (
            <button type="button" onClick={() => void run(() => api.clearFailover(), "Failover flag cleared.")}>
              Clear failover flag
            </button>
          ) : (
            <button type="button" className="danger" onClick={() => void run(() => api.failover(note), "Failover flag set.")}>
              Set failover flag
            </button>
          )}
        </section>
      )}
      {admin && (
        <>
          <section className="card" aria-labelledby="pause-heading">
            <h2 id="pause-heading">Global pause</h2>
            <p>Currently: {data?.paused ? <strong>paused</strong> : "running"}</p>
            {data?.paused ? (
              <button type="button" onClick={() => void run(() => api.resume(), "Replication resumed.")}>
                Resume replication
              </button>
            ) : (
              <button type="button" className="danger" onClick={() => void run(() => api.pause(note), "Replication paused.")}>
                Pause all replication
              </button>
            )}
          </section>
          <section className="card" aria-labelledby="config-heading">
            <h2 id="config-heading">Configuration</h2>
            <p>Validates the configuration file and activates it for new jobs. Running jobs keep their version.</p>
            <button type="button" onClick={() => void run(async () => setMessage(`Configuration ${(await api.reload()).version} is active.`), "Configuration reloaded.")}>
              Reload configuration
            </button>
          </section>
        </>
      )}
    </>
  );
}
