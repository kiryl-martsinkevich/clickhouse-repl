import { useEffect, useRef, useState } from "react";
import type { Job } from "./api";

/** Polls [load] every [ms] while mounted; returns the latest value and error. */
export function usePoll<T>(load: () => Promise<T>, ms: number, deps: unknown[] = []): { data: T | null; error: string | null; reload: () => void } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const loadRef = useRef(load);
  loadRef.current = load;
  useEffect(() => {
    let alive = true;
    const run = () =>
      loadRef
        .current()
        .then((v) => alive && (setData(v), setError(null)))
        .catch((e: Error) => alive && setError(e.message));
    run();
    const timer = setInterval(run, ms);
    return () => {
      alive = false;
      clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ms, tick, ...deps]);
  return { data, error, reload: () => setTick((t) => t + 1) };
}

export function formatBytes(bytes: number): string {
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

export function formatNumber(n: number): string {
  return n.toLocaleString("en-GB");
}

export function ago(iso?: string): string {
  if (!iso) return "—";
  const seconds = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 1000));
  if (seconds < 60) return `${seconds} s ago`;
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)} h ago`;
  return `${Math.round(seconds / 86400)} d ago`;
}

export function duration(seconds?: number): string {
  if (seconds === undefined || seconds === null) return "—";
  if (seconds < 90) return `${seconds} s`;
  if (seconds < 5400) return `${Math.round(seconds / 60)} min`;
  return `${(seconds / 3600).toFixed(1)} h`;
}

/** The state shown to users: RUNNING (or PENDING) jobs held by the guard read as WAITING_FOR_SOURCE (SPEC §7.2). */
export function displayState(job: Job): string {
  return job.waiting_for_source ? "WAITING_FOR_SOURCE" : job.state;
}

/** Rough time to completion from the chunk rate so far. */
export function eta(job: Job): string {
  const { chunks_done, chunks_total } = job.progress;
  if (!job.started_at || chunks_done === 0 || chunks_total === 0 || chunks_done >= chunks_total) return "—";
  const elapsed = (Date.now() - Date.parse(job.started_at)) / 1000;
  return `about ${duration(Math.round((elapsed / chunks_done) * (chunks_total - chunks_done)))}`;
}

export function newKey(): string {
  return crypto.randomUUID();
}
