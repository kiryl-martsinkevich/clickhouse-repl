import { useEffect, useRef, useState } from "react";
import { api, TERMINAL, type Guard, type Job } from "./api";
import { displayState, duration, usePoll } from "./util";

export function StateBadge({ job }: { job: Job }) {
  const state = displayState(job);
  return <span className={`badge badge-${state.toLowerCase()}`}>{state.replace(/_/g, " ")}</span>;
}

export function Progress({ done, total, label }: { done: number; total: number; label: string }) {
  const pct = total === 0 ? 0 : Math.round((done / total) * 100);
  return (
    <span className="progress-wrap">
      <progress max={Math.max(total, 1)} value={done} aria-label={label} />
      <span className="muted">
        {done}/{total} ({pct}%)
      </span>
    </span>
  );
}

export function ErrorNote({ error }: { error: string | null }) {
  if (!error) return null;
  return (
    <p className="error" role="alert">
      {error}
    </p>
  );
}

/** A banner whenever replication from DR is not freely running (SPEC §7.1 step 5, PLAN M6). */
export function GuardBanner() {
  const { data } = usePoll<Guard>(() => api.guard(), 5000);
  if (!data) return null;
  let text: string | null = null;
  let kind = "warn";
  if (data.failover || data.level === "DR_ACTIVE") {
    text = `DR may be active (failover). No replication reads run until an operator clears it. ${data.failover?.message ?? ""}`;
    kind = "danger";
  } else if (data.paused) {
    text = "Replication is paused by an administrator. Requests are queued and start when it resumes.";
  } else if (data.guarded && data.level && data.level !== "GREEN") {
    const reasons = data.reasons.map((r) => r.message).join("; ");
    text = `The DR cluster is busy (${data.level}); copies wait until it is free. ${reasons}`;
  }
  return (
    <div role="status" aria-live="polite" className={text ? `banner banner-${kind}` : "sr-only"}>
      {text ?? `Source guard ${data.level ?? "unguarded"}; DR delay ${duration(data.replica_delay_seconds)}`}
    </div>
  );
}

/** Notifies when one of the user's jobs finishes (SPEC §7.1 step 6). */
export function Notifier() {
  const { data } = usePoll<Job[]>(() => api.jobs(true), 5000);
  const previous = useRef<Map<string, string>>(new Map());
  const [messages, setMessages] = useState<{ id: string; text: string; ok: boolean }[]>([]);
  useEffect(() => {
    if (!data) return;
    const seen = previous.current;
    const fresh: { id: string; text: string; ok: boolean }[] = [];
    for (const job of data) {
      const before = seen.get(job.id);
      if (before && !TERMINAL.includes(before as Job["state"]) && TERMINAL.includes(job.state)) {
        fresh.push({ id: job.id, text: `Job ${job.id} ${job.state.toLowerCase()} (${job.dataset ?? job.tables.join(", ")} → ${job.target})`, ok: job.state === "SUCCEEDED" });
      }
      seen.set(job.id, job.state);
    }
    if (fresh.length) setMessages((m) => [...m, ...fresh].slice(-4));
  }, [data]);
  return (
    <div className="toasts" role="status" aria-live="polite">
      {messages.map((m) => (
        <div key={m.id} className={`toast ${m.ok ? "toast-ok" : "toast-bad"}`}>
          <a href={`#/jobs/${m.id}`}>{m.text}</a>
          <button type="button" className="link" aria-label={`Dismiss ${m.text}`} onClick={() => setMessages((all) => all.filter((x) => x.id !== m.id))}>
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
