// Thin client of the REST API (docs/SPEC.md section 7.5). Field names are the API's snake_case.

import { token } from "./auth";

export type Role = "viewer" | "requester" | "operator" | "admin";

export interface Me {
  user: string;
  groups: string[];
  roles: Role[];
}

export interface Catalog {
  datasets: { name: string; description?: string; tables: string[] }[];
  tables: { name: string; cob_column?: string; full_copy: boolean }[];
  targets: string[];
}

export interface RecentCopy {
  table: string;
  cob_date?: string;
  requester: string;
  job_id: string;
  copied_at: string;
}

export interface Estimate {
  tables: { table: string; partitions: number; rows: number; bytes: number }[];
  bytes: number;
  source_available: boolean;
  source_reason?: string;
  queued_jobs: number;
  recent_copies: RecentCopy[];
  confirmation_required: boolean;
}

export type JobState =
  | "PENDING"
  | "PLANNING"
  | "RUNNING"
  | "FINALISING"
  | "SUCCEEDED"
  | "FAILED"
  | "BLOCKED"
  | "CANCELLING"
  | "CANCELLED";

export const TERMINAL: JobState[] = ["SUCCEEDED", "FAILED", "BLOCKED", "CANCELLED"];

export interface Job {
  id: string;
  kind: string;
  requester: string;
  requesters: string[];
  target: string;
  dataset?: string;
  tables: string[];
  cob_dates: string[];
  state: JobState;
  wait_reason?: string;
  waiting_for_source: boolean;
  reason?: string;
  queue_position?: number;
  progress: { chunks_total: number; chunks_done: number; partitions_total: number; partitions_done: number; rows_copied: number };
  estimated_bytes: number;
  config_version: string;
  created_at: string;
  started_at?: string;
  finished_at?: string;
}

export interface Chunk {
  id: number;
  table: string;
  shard: string;
  partition_id: string;
  state: string;
  attempts: number;
  preemptions: number;
  rows?: number;
  wait_reason?: string;
  error?: string;
}

export interface Reason {
  code: string;
  host?: string;
  message: string;
}

export interface Guard {
  level?: string;
  hosts: Record<string, string>;
  reasons: Reason[];
  active_reads: number;
  paused: boolean;
  failover?: Reason;
  guarded: boolean;
  replica_delay_seconds?: number;
}

export interface Stream {
  name: string;
  table: string;
  target: string;
  mode: string;
  status: string;
  paused: boolean;
  last_poll_at?: string;
  last_success_at?: string;
  synced_as_of?: string;
  lag_seconds?: number;
  lag_slo_seconds?: number;
  slo_breached: boolean;
  alerting: boolean;
  job_id?: string;
  wait_reason?: string;
  last_error?: string;
}

export interface SubmitBody {
  dataset?: string;
  tables?: string[];
  cob?: { dates?: string[]; from?: string; to?: string };
  target: string;
  mode: "replace" | "skip_if_present";
  confirm_overwrite_recent?: boolean;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/** Called when the API says the token is missing or expired. */
let onUnauthorised: () => void = () => {};
export function setUnauthorisedHandler(handler: () => void): void {
  onUnauthorised = handler;
}

async function call<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  const auth = token();
  const response = await fetch(`/api/v1${path}`, {
    method,
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(auth ? { Authorization: `Bearer ${auth}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 401) {
    onUnauthorised();
    throw new ApiError(401, "unauthorised", "your session has expired; signing in again");
  }
  const text = await response.text();
  const json = text ? JSON.parse(text) : undefined;
  if (!response.ok) throw new ApiError(response.status, json?.error ?? "error", json?.message ?? `HTTP ${response.status}`);
  return json as T;
}

export const api = {
  me: () => call<Me>("GET", "/me"),
  catalog: () => call<Catalog>("GET", "/catalog"),
  estimate: (body: SubmitBody) => call<Estimate>("POST", "/estimates", body),
  submit: (body: SubmitBody, idempotencyKey: string) => call<Job>("POST", "/jobs", body, { "Idempotency-Key": idempotencyKey }),
  jobs: (mine: boolean) => call<Job[]>("GET", `/jobs?mine=${mine}&limit=200`),
  job: (id: string) => call<Job>("GET", `/jobs/${encodeURIComponent(id)}`),
  chunks: (id: string) => call<Chunk[]>("GET", `/jobs/${encodeURIComponent(id)}/chunks`),
  cancel: (id: string) => call<Job>("POST", `/jobs/${encodeURIComponent(id)}/cancel`),
  history: (target: string, table?: string, cob?: string) => {
    const q = new URLSearchParams();
    if (table) q.set("table", table);
    if (cob) q.set("cob", cob);
    return call<RecentCopy[]>("GET", `/targets/${encodeURIComponent(target)}/history?${q}`);
  },
  guard: () => call<Guard>("GET", "/guard"),
  continuous: () => call<Stream[]>("GET", "/continuous"),
  pauseStream: (name: string) => call<void>("POST", `/continuous/${encodeURIComponent(name)}/pause`),
  resumeStream: (name: string) => call<void>("POST", `/continuous/${encodeURIComponent(name)}/resume`),
  pause: (note: string) => call<void>("POST", "/admin/pause", { note }),
  resume: () => call<void>("POST", "/admin/resume"),
  failover: (note: string) => call<void>("POST", "/admin/failover", { note }),
  clearFailover: () => call<void>("POST", "/admin/failover/clear"),
  reload: () => call<{ version: string }>("POST", "/admin/config/reload"),
};

export function hasRole(me: Me | null, role: Role): boolean {
  return !!me && me.roles.includes(role);
}
