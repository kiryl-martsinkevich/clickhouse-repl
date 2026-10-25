-- Rekey jobs (SPEC §6.2): may overwrite target tables masked with an older key version.
ALTER TABLE jobs ADD COLUMN rekey boolean NOT NULL DEFAULT false;
