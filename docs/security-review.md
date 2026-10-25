# Security review (PLAN M7)

**Scope:**
- the control plane API and the portal;
- DR and target access;
- secrets;
- the container image and the Helm chart.

**Method:** code review against SPEC §10 (security) plus the automated checks the build runs:
- ktlint and compile warnings as errors;
- OSV dependency scanning (see `SECURITY.md`);
- the portal E2E with axe;
- the IT suite's "never contacted the active DC" assertions.

This is a self-review by the implementer. An independent review or pen test is still required
before production (see "Open items").

## Production isolation

| Control | Implementation | Verified by |
|---|---|---|
| Never connect to the active DC | Config validation rejects active-DC hosts/CIDRs (DNS-resolved at start-up). A runtime re-check every minute sets the sticky failover flag and counts `chrepl_active_dc_violations_total`. The Helm NetworkPolicy allows egress only to DR, targets, Postgres, OIDC, the key store and DNS. | `NegativeConfigTest`, `ActiveDcWatchTest`; every replicated IT asserts the prod honeypot users were never used |
| Reads only through the guard | `CopySession` refuses an unguarded gate for non-`test` sources. Planning waits for GREEN or AMBER. Continuous-mode metadata and watermark reads are admitted like chunk reads. | `CopyIT` ("unguarded copy from a DR source is refused"), `GuardIT`, `ContinuousIT` (no reads while RED) |
| Least privilege on DR | `repl_reader`: SELECT on managed databases and a few system tables, enforced profile (bandwidth, threads, `workload`, `force_index_by_date`), quota. `repl_guard`: system tables, `SHOW TABLES`, `KILL QUERY`. | `GuardIT` ("the DR user profile enforces the L1 limits") |
| Target safety | The environment marker is checked before every write. Writer grants cover only managed databases and `repl_meta`. A key-version check refuses to mix masking keys. | `CopyIT` (wrong marker), `MaintenanceIT` (rekey) |

## API and portal

| Area | Finding | Status |
|---|---|---|
| AuthN | Bearer JWTs validated against the configured issuer (signature, expiry, issuer). Without a JWT decoder the app does not start. | OK. **Recommend** setting `spring.security.oauth2.resourceserver.jwt.audiences` (Helm `oidc.audiences`) so tokens issued for other clients are refused. |
| AuthZ | RBAC in the services, not just the controllers, so CLI, API and portal share it. Operator and admin actions are role-checked and audited. Users cancel only their own jobs; operators cancel any. | OK (`ApiIT`, `ControlPlaneIT`) |
| Injection | API inputs are validated against the config (tables, datasets, targets) or typed (dates, enums). Postgres uses prepared statements throughout. ClickHouse queries use typed parameters (`{name:Type}`); the few interpolated identifiers come from config or `system.*` and are quoted with `Sql.ident`/`Sql.string`. | OK |
| Data leakage via errors | ClickHouse error messages can quote values, and job reasons and chunk errors are visible to every viewer. | **Fixed:** quoted literals are redacted (`Worker.describe`, `DescribeTest`). |
| Browser | PKCE (S256) with a state check. Token in memory only. Strict CSP (no inline scripts or styles; `connect-src`/`form-action` = self + issuer; `frame-ancestors 'none'`). `Referrer-Policy: no-referrer`. React escaping; no `dangerouslySetInnerHTML`. | OK |
| CSRF | No cookies; bearer tokens only. | Not applicable |
| Operational endpoints | `/actuator/prometheus` and health are unauthenticated: they expose table and host names, no data. | Helm puts actuator on port 8081, which neither the Service nor the Ingress exposes. `/v3/api-docs` and Swagger UI are disabled by default in Helm (`apiDocs.enabled`). |
| Abuse limits | COB ranges are capped (366 days, then the quota). List limits are capped (500). Quotas apply per group (bytes per job and per day, concurrent jobs, COB dates). Idempotency keys are length-capped. | OK. No per-IP rate limiting (left to the ingress). |
| Dev IdP | `modules/devidp` signs tokens for any listed user without a password. | Dev and test only; not in the image or the Helm chart. **Never deploy it.** |

## Secrets and keys

- Secrets are always references (`${file:…}`, `${env:…}`, `${fortanix:…}`), never literals; validation rejects literals. Helm mounts them read-only with mode 0400.
- The masking key lives in worker memory only. It is not logged, not sent to ClickHouse, and the audit log records the key version only.
- ClickHouse credentials are sent as `X-ClickHouse-User`/`X-ClickHouse-Key` headers over TLS, never in URLs.

## Supply chain and runtime

- The image is the Chainguard JRE pinned by digest: no shell or package manager, non-root (uid 65532), no known CVEs at the last scan. The root filesystem is read-only with an emptyDir `/tmp`. All capabilities are dropped and seccomp is RuntimeDefault.
- Dependencies are pinned (Gradle version catalog, `package-lock.json`). CI scans them with OSV-Scanner (see `SECURITY.md`).

## Open items (need people or environments)

1. An independent pen test of the portal and API in staging.
2. The audit log is append-only by convention. **Recommend** revoking `UPDATE`/`DELETE` on `audit_log` from the application role and exporting the table to the SIEM.
3. A DB credential rotation procedure (Postgres, ClickHouse users): today it means a secret update plus a rolling restart.
4. Review of the Fortanix DSM integration against the real DSM tenant (M8 was tested against a mock).
