# Runtime image for the control plane (all roles). Build the jar first:
#   ./gradlew :app:bootJar && docker build -t clickhouse-repl:dev .
# Chainguard JRE: minimal (no shell, no package manager), non-root (uid 65532) and, when last
# scanned (OSV-Scanner, see SECURITY.md), free of known CVEs. Pinned by digest; bump it to pick up fixes.
FROM cgr.dev/chainguard/jre:latest@sha256:e91c6abce7e784a90111f265e2556e361417905c1c229877bae1a815b8dad5af
WORKDIR /app
COPY modules/app/build/libs/clickhouse-repl-app.jar /app/app.jar
USER 65532:65532
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
