# Runtime image for the control plane (all roles). Build the jar first:
#   ./gradlew :app:bootJar && docker build -t clickhouse-repl:dev .
# Distroless: no shell or package manager, runs as non-root (uid 65532).
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY modules/app/build/libs/clickhouse-repl-app.jar /app/app.jar
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
