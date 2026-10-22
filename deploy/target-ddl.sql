-- Run by the DBAs on every host of a NON-PRODUCTION target (SPEC §10). Never on DR or prod.
-- Replace <target> with the target's name from the config (e.g. qa1) and list the managed databases.

-- The environment marker the tool checks before every write. Only DBAs may change it.
CREATE DATABASE IF NOT EXISTS repl_meta;
CREATE TABLE IF NOT EXISTS repl_meta.environment (value String) ENGINE = MergeTree ORDER BY tuple();
INSERT INTO repl_meta.environment VALUES ('<target>');

-- The writer: least privileges. It can read the marker but never change it.
CREATE USER IF NOT EXISTS repl_writer IDENTIFIED WITH sha256_password BY '<secret>' HOST IP '10.20.0.0/16';
GRANT SELECT ON repl_meta.environment TO repl_writer;
GRANT CREATE TABLE, SELECT, INSERT ON repl_meta.tables TO repl_writer;
GRANT CREATE TABLE, SELECT, INSERT ON repl_meta.copy_history TO repl_writer;
-- One line per managed database (the databases of the configured tables):
GRANT CREATE DATABASE, ALL ON trading.* TO repl_writer;
GRANT CREATE DATABASE, ALL ON crm.* TO repl_writer;
GRANT CREATE DATABASE, ALL ON refdata.* TO repl_writer;
GRANT SELECT ON system.* TO repl_writer;
