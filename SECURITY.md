# Security

## Reporting

Please report vulnerabilities privately to the repository owner rather than in a public issue.

## Dependency and image scanning

The CI `security` job checks every dependency the project ships or builds with, and fails on any
known vulnerability:

| What | How | Where pinned |
|---|---|---|
| JVM dependencies of every module (runtime, test, and the ktlint tool) | [OSV-Scanner](https://github.com/google/osv-scanner) on the Gradle lockfiles | `modules/*/gradle.lockfile` (refresh: `./gradlew resolveAndLockAll --write-locks`) |
| Portal (npm) | OSV-Scanner on `package-lock.json` and `npm audit` | `modules/portal/package-lock.json` |
| Container image | OSV-Scanner image scan of the built image | `Dockerfile` base image, pinned by digest |

Dependabot (`.github/dependabot.yml`) proposes updates weekly for Gradle, npm, the Docker base
image and GitHub Actions.

### Results (2026-10-25)

Scanned with OSV-Scanner 2.2.4 and `npm audit`: **no known vulnerabilities**.

| What | Packages | Findings |
|---|---|---|
| JVM dependencies | 11 Gradle lockfiles | 0 |
| Build plugins (`buildEnvironment`, build-logic) | 52 packages | 0 |
| Portal | `package-lock.json` (73 packages) | 0 |
| Image | `clickhouse-repl` on the Chainguard JRE | 0 |

Fixed along the way:

| Finding | Where | Fix |
|---|---|---|
| `logback-core` 1.3.14 (GHSA-pr98-23f8-jwxv, GHSA-25qh-j22f-pwp8 and 4 more) | the ktlint lint tool's classpath | aligned to 1.5.38 |
| `commons-lang3` 3.16.0 (GHSA-j288-q9x7-2f5v) | the Spring Boot Gradle plugin's buildpack support | pinned to 3.21.0 |
| OS packages with open CVEs (glibc, expat, libpng and others, many without a Debian fix) | the base image `gcr.io/distroless/java21-debian12` | moved to `cgr.dev/chainguard/jre`, which has none |

The Chainguard JRE is currently Java 27. The application is built for Java 21 bytecode, and the
demo and the image smoke test run on it. If a Java 21 LTS runtime is required, `eclipse-temurin:21-jre-alpine`
is the closest alternative; at the time of the scan it had one open, unfixed `brotli` advisory.

The ClickHouse and Postgres images used by the integration tests and the demo are not part of the
product and are not scanned.
